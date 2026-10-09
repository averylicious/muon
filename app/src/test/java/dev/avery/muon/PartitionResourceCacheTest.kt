package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #253 unwired single-resource partition cache over a real SimpleCache with native SQLite and real span
 * files. Every refusal is checked against the native cache and the files on disk: nothing it refuses may
 * reach the native index, and no original byte or metadata field may change. One case drives the actual
 * CacheDataSource + CacheWriter lifecycle through the wrapper. No network, device or production routing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PartitionResourceCacheTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private val caches = ArrayList<SimpleCache>()
    private val key = "saved/partition-resource"
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    @Before fun setUp() { database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() {
        try { caches.forEach { runCatching { it.release() } } } finally { database.close() }
    }

    @Test fun theActualCacheWriterLifecycleCommitsThroughTheWrapperAndLeavesNothingHeld() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key)
        val source = CacheDataSource.Factory().setCache(partition)
            .setUpstreamDataSourceFactory { Bytes(payload) }.createDataSourceForDownloading()
        CacheWriter(source, DataSpec.Builder().setUri(Uri.parse("fixture://audio")).setKey(key).build(), null, null).cache()
        assertTrue(cache.isCached(key, 0, payload.size.toLong()))
        assertEquals(payload.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(key)))
        assertArrayEquals(payload, bytes(cache))
        assertFalse(partition.uncertain)
        partition.release() // Allowed: every write lock, file and listener was returned.
    }

    @Test fun otherKeysForeignFilesForeignHolesAndLookAlikeSpansNeverReachTheNativeCache() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key)
        write(partition, 0, payload)
        partition.applyContentMetadataMutations(key,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        val original = cache.getCachedSpans(key).single()

        // Another key: refused before Media3 would create its content record.
        assertThrows(IllegalArgumentException::class.java) { partition.startReadWriteNonBlocking("other", 0, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            partition.applyContentMetadataMutations("other", ContentMetadataMutations().set("x", "y"))
        }
        assertThrows(IllegalArgumentException::class.java) { partition.getCachedSpans("other") }
        assertEquals(setOf(key), cache.keys)

        // A real span, hole and file belonging to a different cache under the same key.
        val other = fresh()
        val otherHole = requireNotNull(other.startReadWriteNonBlocking(key, 0, payload.size.toLong()))
        val otherFile = other.startFile(key, 0, payload.size.toLong()).apply { writeBytes(ByteArray(payload.size) { 7 }) }
        assertThrows(Cache.CacheException::class.java) { partition.commitFile(otherFile, payload.size.toLong()) }
        assertTrue("The foreign file is untouched", otherFile.exists())
        assertThrows(IllegalArgumentException::class.java) { partition.releaseHoleSpan(otherHole) }
        other.commitFile(otherFile, payload.size.toLong())
        other.releaseHoleSpan(otherHole)
        val lookAlike = other.getCachedSpans(key).single()
        assertEquals(original.position, lookAlike.position)
        assertEquals(original.length, lookAlike.length)
        // Media3 matches spans by key and position, then deletes the file of the span it was given.
        assertThrows(IllegalArgumentException::class.java) { partition.removeSpan(lookAlike) }
        assertTrue("The look-alike's own file is untouched", requireNotNull(lookAlike.file).exists())

        assertEquals(listOf(original.file), cache.getCachedSpans(key).map { it.file })
        assertArrayEquals(payload, bytes(cache))
        assertArrayEquals(ByteArray(payload.size) { 7 }, bytes(other))
        assertFalse(partition.uncertain)
    }

    @Test fun admissionRefusesAnotherResourceUnknownOverBudgetStateWithoutChangingIt() {
        val shared = fresh()
        rawWrite(shared, key, 0, payload)
        shared.applyContentMetadataMutations("another", ContentMetadataMutations().set("kept", "value"))
        assertThrows(IOException::class.java) { PartitionResourceCache.open(shared, key) }
        assertEquals("value", shared.getContentMetadata("another").get("kept", null as String?))
        assertArrayEquals(payload, bytes(shared))

        val crowded = fresh()
        rawWrite(crowded, key, 0, payload)
        val mutations = ContentMetadataMutations()
        repeat(3) { mutations.set("unknown-$it", byteArrayOf(it.toByte())) }
        crowded.applyContentMetadataMutations(key, mutations)
        assertThrows(IOException::class.java) {
            PartitionResourceCache.open(crowded, key, PartitionResourceLimits(metadataFields = 2))
        }
        assertEquals(3, (crowded.getContentMetadata(key) as DefaultContentMetadata).entrySet().size)

        val fragmented = fresh()
        rawWrite(fragmented, key, 0, payload.copyOfRange(0, 100))
        rawWrite(fragmented, key, 100, payload.copyOfRange(100, 200))
        rawWrite(fragmented, key, 200, payload.copyOfRange(200, 300))
        assertThrows(IOException::class.java) { PartitionResourceCache.open(fragmented, key, PartitionResourceLimits(spans = 2)) }
        assertEquals(3, fragmented.getCachedSpans(key).size)

        assertThrows(IOException::class.java) { PartitionResourceCache.open(fresh(), "K".repeat(9), PartitionResourceLimits(keyBytes = 16)) }
    }

    @Test fun adjacentSpansCoalesceIntoOneRangeButEachCountsAgainstTheNativeSpanBudget() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key, PartitionResourceLimits(spans = 2))
        write(partition, 0, payload.copyOfRange(0, 1000))
        write(partition, 1000, payload.copyOfRange(1000, 2000))
        assertEquals("One coalesced range", 2000L, cache.getCachedLength(key, 0, Long.MAX_VALUE))
        assertEquals("Two native spans", 2, cache.getCachedSpans(key).size)
        val hole = requireNotNull(partition.startReadWriteNonBlocking(key, 2000, 1000))
        assertThrows(Cache.CacheException::class.java) { partition.startFile(key, 2000, 1000) }
        partition.releaseHoleSpan(hole)
        assertEquals(2, cache.getCachedSpans(key).size)
        assertArrayEquals(payload.copyOfRange(0, 2000), bytes(cache))
    }

    @Test fun outstandingStartFilesReserveSpansHolesAndFilesUntilCommitOrRelease() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key, PartitionResourceLimits(spans = 2, holes = 2, files = 1))
        write(partition, 0, payload.copyOfRange(0, 100))
        val first = requireNotNull(partition.startReadWriteNonBlocking(key, 100, 100))
        val second = requireNotNull(partition.startReadWriteNonBlocking(key, 200, 100))
        assertThrows("Hole budget", Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key, 300, 100) }
        val pending = partition.startFile(key, 100, 100) // Reserves the last span.
        assertThrows("File and span budget", Cache.CacheException::class.java) { partition.startFile(key, 200, 100) }
        assertThrows("Outside every held lock", Cache.CacheException::class.java) { partition.startFile(key, 500, 10) }

        // A writer that fails before committing: releasing its lock frees the file and its span reservation.
        pending.writeBytes(payload.copyOfRange(100, 200))
        partition.releaseHoleSpan(first)
        assertThrows("A released lock's file can no longer be committed", Cache.CacheException::class.java) {
            partition.commitFile(pending, 100)
        }
        assertEquals(1, cache.getCachedSpans(key).size)
        val retry = partition.startFile(key, 200, 100).apply { writeBytes(payload.copyOfRange(200, 300)) }
        assertThrows("Longer than started", Cache.CacheException::class.java) { partition.commitFile(retry, 101) }
        partition.commitFile(retry, 100)
        partition.releaseHoleSpan(second)
        assertEquals(2, cache.getCachedSpans(key).size)
        assertArrayEquals(payload.copyOfRange(0, 100), cache.getCachedSpans(key).first().file!!.readBytes())
        partition.release()
    }

    @Test fun metadataBudgetsAreCheckedOnTheMergedResultAndUnknownFieldsSurvive() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key, PartitionResourceLimits(metadataFields = 3, metadataBytes = 64))
        // Metadata alone, with no bytes and no held lock, would be dropped by Media3: refused, nothing created.
        assertThrows(Cache.CacheException::class.java) {
            partition.applyContentMetadataMutations(key, ContentMetadataMutations().set("early", "x"))
        }
        assertTrue(cache.keys.isEmpty())

        write(partition, 0, payload)
        partition.applyContentMetadataMutations(key, ContentMetadataMutations().set("unknown", byteArrayOf(1, 2, 3)))
        assertThrows("Too many fields", Cache.CacheException::class.java) {
            partition.applyContentMetadataMutations(key, ContentMetadataMutations().set("b", "1").set("c", "2").set("d", "3"))
        }
        assertThrows("Too many bytes", Cache.CacheException::class.java) {
            partition.applyContentMetadataMutations(key, ContentMetadataMutations().set("big", ByteArray(64)))
        }
        // Removing a field to make room is the caller's explicit choice, not something admission does.
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.getContentMetadata(key).get("unknown", null as ByteArray?))
        assertEquals(1, (cache.getContentMetadata(key) as DefaultContentMetadata).entrySet().size)

        partition.applyContentMetadataMutations(key,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.getContentMetadata(key).get("unknown", null as ByteArray?))
        // A declared length below the retained bytes would hide them from readers.
        assertThrows(Cache.CacheException::class.java) {
            partition.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 10))
        }
        assertEquals(payload.size.toLong(), ContentMetadata.getContentLength(cache.getContentMetadata(key)))
        assertArrayEquals(payload, bytes(cache))
    }

    @Test fun aMetadataOnlyResourceIsAdmittedReadOnlyBecauseMedia3DropsItOnAHoleRelease() {
        // Pinned Media3 behavior, on a separate raw cache: releasing a hole on a bytes-less resource drops it.
        val raw = fresh()
        raw.applyContentMetadataMutations("probe", ContentMetadataMutations().set("kept", "value"))
        raw.releaseHoleSpan(requireNotNull(raw.startReadWriteNonBlocking("probe", 0, 10)))
        assertNull(raw.getContentMetadata("probe").get("kept", null as String?))

        val cache = fresh()
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set("kept", "value"))
        val partition = PartitionResourceCache.open(cache, key)
        assertThrows(Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key, 0, 10) }
        assertThrows(Cache.CacheException::class.java) { partition.startReadWrite(key, 0, 10) }
        assertEquals("value", cache.getContentMetadata(key).get("kept", null as String?))
        partition.removeResource(key) // No spans: Media3 removes nothing, and the metadata stays.
        assertEquals("value", cache.getContentMetadata(key).get("kept", null as String?))
    }

    @Test fun removalIsExplicitRefusedDuringWritesAndNeverDropsMetadataWithAPartialSpanRemoval() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key)
        write(partition, 0, payload)
        partition.applyContentMetadataMutations(key, ContentMetadataMutations().set("unknown", "kept"))
        val only = cache.getCachedSpans(key).single()
        assertThrows(IllegalStateException::class.java) { partition.removeSpan(only) }
        val hole = requireNotNull(partition.startReadWriteNonBlocking(key, payload.size.toLong(), 10))
        assertThrows(IllegalStateException::class.java) { partition.removeResource(key) }
        partition.releaseHoleSpan(hole)
        assertArrayEquals(payload, bytes(cache))
        assertEquals("kept", cache.getContentMetadata(key).get("unknown", null as String?))
        partition.removeResource(key)
        assertTrue(cache.getCachedSpans(key).isEmpty())
    }

    @Test fun aBlockedWriterHoldsItsReservationAndAnInterruptedOneReturnsIt() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key, PartitionResourceLimits(holes = 2))
        val held = requireNotNull(partition.startReadWriteNonBlocking(key, 0, C.LENGTH_UNSET.toLong()))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reader = workers.submit(Callable { partition.startReadWrite(key, 0, payload.size.toLong()) })
            // While that caller waits it holds the second slot: a third lock is refused, not queued. (The held
            // open-ended lock covers 50_000, so a probe made before the reservation returns null, never a lock.)
            waitUntil { refused { partition.startReadWriteNonBlocking(key, 50_000, 10) } }
            val file = partition.startFile(key, 0, payload.size.toLong()).apply { writeBytes(payload) }
            partition.commitFile(file, payload.size.toLong())
            partition.releaseHoleSpan(held)
            val span = reader.get(5, TimeUnit.SECONDS)
            assertTrue("The waiter now reads the committed bytes", span.isCached)

            val lock = requireNotNull(partition.startReadWriteNonBlocking(key, payload.size.toLong(), C.LENGTH_UNSET.toLong()))
            val finished = CountDownLatch(1)
            val blocked = workers.submit(Callable {
                try { partition.startReadWrite(key, payload.size.toLong(), 10) } catch (_: InterruptedException) { null }
                finally { finished.countDown() }
            })
            waitUntil { refused { partition.startReadWriteNonBlocking(key, 50_000, 10) } }
            blocked.cancel(true) // Interrupts the waiting caller.
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            partition.releaseHoleSpan(lock)
            // Both reservations are back: two new locks fit, and release succeeds once they are returned.
            val a = requireNotNull(partition.startReadWriteNonBlocking(key, 10_000, 10))
            val b = requireNotNull(partition.startReadWriteNonBlocking(key, 20_000, 10))
            partition.releaseHoleSpan(a); partition.releaseHoleSpan(b)
        } finally { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS) }
        assertArrayEquals(payload, bytes(cache))
        partition.release()
    }

    @Test fun aNativeFailureLeavesTheInstanceUncertainAndRefusingEveryLaterMutation() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key)
        write(partition, 0, payload)
        val hole = requireNotNull(partition.startReadWriteNonBlocking(key, payload.size.toLong(), 10))
        val file = partition.startFile(key, payload.size.toLong(), 10).apply { writeBytes(ByteArray(10)) }
        cache.release() // The native cache goes away behind the wrapper, as an owner failure would.
        assertThrows(IllegalStateException::class.java) { partition.commitFile(file, 10) }
        assertTrue(partition.uncertain)
        assertThrows(Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key, 0, 1) }
        assertThrows(Cache.CacheException::class.java) {
            partition.applyContentMetadataMutations(key, ContentMetadataMutations().set("x", "y"))
        }
        assertThrows(IllegalStateException::class.java) { partition.removeResource(key) }
        assertNotNull(hole)
        // The original committed bytes remain on disk.
        assertTrue(folders.root.walk().any { it.isFile && it.name.endsWith(".exo") && it.readBytes().contentEquals(payload) })
    }

    @Test fun releaseIsRefusedWhileLocksFilesOrListenersAreOutstanding() {
        val cache = fresh()
        val partition = PartitionResourceCache.open(cache, key, PartitionResourceLimits(listeners = 1))
        val listener = object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) = Unit
            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }
        partition.addListener(key, listener)
        assertThrows(IllegalStateException::class.java) { partition.addListener(key, object : Cache.Listener {
            override fun onSpanAdded(cache: Cache, span: CacheSpan) = Unit
            override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
            override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
        }) }
        assertThrows(IllegalStateException::class.java) { partition.release() }
        partition.removeListener(key, listener)
        val hole = requireNotNull(partition.startReadWriteNonBlocking(key, 0, 10))
        assertThrows(IllegalStateException::class.java) { partition.release() }
        partition.releaseHoleSpan(hole)
        partition.release()
        assertThrows(IllegalStateException::class.java) { partition.getCachedSpans(key) }
    }

    @Test fun callbackUsesTheFacadeAndCannotReenterWritesPastTheSpanBudget() {
        val native=fresh()
        val partition=PartitionResourceCache.open(native,key,PartitionResourceLimits(spans=1))
        var calls=0
        val listener=object:Cache.Listener {
            override fun onSpanAdded(cache:Cache,span:CacheSpan) {
                calls++; assertSame(partition,cache)
                assertThrows(IllegalStateException::class.java) { cache.startReadWriteNonBlocking(key,10000,10) }
                assertThrows(IllegalStateException::class.java) { cache.removeResource(key) }
                assertThrows(IllegalStateException::class.java) { cache.release() }
            }
            override fun onSpanRemoved(cache:Cache,span:CacheSpan)=Unit
            override fun onSpanTouched(cache:Cache,old:CacheSpan,next:CacheSpan)=Unit
        }
        partition.addListener(key,listener)
        write(partition,0,payload)
        assertEquals(1,calls); assertEquals(1,native.getCachedSpans(key).size)
        assertFalse(partition.uncertain); assertArrayEquals(payload,bytes(native))
        partition.removeListener(key,listener); partition.release()
    }

    @Test fun oversizedConfiguredBudgetsAndOverflowingWritesRefuseBeforeNativeMutation() {
        assertThrows(IllegalArgumentException::class.java) { PartitionResourceLimits(spans=MIGRATION_RANGES+1) }
        assertThrows(IllegalArgumentException::class.java) { PartitionResourceLimits(holes=Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { PartitionResourceLimits(files=Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { PartitionResourceLimits(metadataBytes=MIGRATION_METADATA_BYTES.toLong()+1) }
        val native=fresh(); val partition=PartitionResourceCache.open(native,key)
        assertThrows(Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key,Long.MAX_VALUE-2,10) }
        assertThrows(Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key,0,-2) }
        assertTrue(native.keys.isEmpty())
        val hole=requireNotNull(partition.startReadWriteNonBlocking(key,0,C.LENGTH_UNSET.toLong()))
        assertThrows(Cache.CacheException::class.java) { partition.startFile(key,Long.MAX_VALUE-2,10) }
        assertThrows(Cache.CacheException::class.java) { partition.startFile(key,0,-2) }
        val file=partition.startFile(key,Long.MAX_VALUE-2,C.LENGTH_UNSET.toLong()).apply { writeBytes(byteArrayOf(1,2,3)) }
        assertThrows(Cache.CacheException::class.java) { partition.commitFile(file,3) }
        assertTrue(file.exists()); assertEquals(0L,native.cacheSpace)
        partition.releaseHoleSpan(hole)
    }

    @Test fun mismatchedCommittedLengthPreservesEveryStagedByteAndCanBeRetriedExactly() {
        val native=fresh(); val partition=PartitionResourceCache.open(native,key)
        val hole=requireNotNull(partition.startReadWriteNonBlocking(key,0,10))
        val file=partition.startFile(key,0,10).apply { writeBytes(byteArrayOf(1,2,3,4)) }
        assertThrows(Cache.CacheException::class.java) { partition.commitFile(file,3) }
        assertThrows(Cache.CacheException::class.java) { partition.commitFile(file,5) }
        assertArrayEquals(byteArrayOf(1,2,3,4),file.readBytes()); assertEquals(0L,native.cacheSpace)
        partition.commitFile(file,4); partition.releaseHoleSpan(hole)
        assertArrayEquals(byteArrayOf(1,2,3,4),bytes(native))
    }

    @Test fun nativeReadWriteAndStartFileFailuresRevokeFutureMutationAdmission() {
        val native=fresh(); val partition=PartitionResourceCache.open(native,key)
        native.release()
        assertThrows(IllegalStateException::class.java) { partition.startReadWriteNonBlocking(key,0,1) }
        assertTrue(partition.uncertain)
        assertThrows(Cache.CacheException::class.java) { partition.startReadWriteNonBlocking(key,0,1) }
        val other=fresh(); val writer=PartitionResourceCache.open(other,key)
        requireNotNull(writer.startReadWriteNonBlocking(key,0,1)); other.release()
        assertThrows(IllegalStateException::class.java) { writer.startFile(key,0,1) }
        assertTrue(writer.uncertain)
    }

    @Test fun emptyExactKeysArePreservedRatherThanRetagged() {
        val native=fresh(); val partition=PartitionResourceCache.open(native,"")
        val hole=requireNotNull(partition.startReadWriteNonBlocking("",0,1))
        val file=partition.startFile("",0,1).apply { writeBytes(byteArrayOf(7)) }
        partition.commitFile(file,1); partition.releaseHoleSpan(hole)
        assertEquals(setOf(""),partition.keys); assertTrue(native.isCached("",0,1)); partition.release()
    }

    // ---- fixture ----

    private fun fresh(): SimpleCache =
        SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database).also { it.checkInitialization(); caches += it }

    /** One write through the wrapper's own lock/file/commit protocol. */
    private fun write(partition: PartitionResourceCache, position: Long, bytes: ByteArray) {
        val hole = requireNotNull(partition.startReadWriteNonBlocking(key, position, bytes.size.toLong()))
        try {
            val file = partition.startFile(key, position, bytes.size.toLong())
            file.writeBytes(bytes)
            partition.commitFile(file, bytes.size.toLong())
        } finally { partition.releaseHoleSpan(hole) }
    }

    private fun rawWrite(cache: SimpleCache, name: String, position: Long, bytes: ByteArray) {
        val hole = requireNotNull(cache.startReadWriteNonBlocking(name, position, bytes.size.toLong()))
        try {
            val file = cache.startFile(name, position, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
    }

    private fun bytes(cache: SimpleCache): ByteArray = cache.getCachedSpans(key).sortedBy { it.position }
        .fold(ByteArray(0)) { all, span -> all + requireNotNull(span.file).readBytes() }

    /** Whether [attempt] is refused by the hole budget; a lock it happens to get is released at once. */
    private fun refused(attempt: () -> CacheSpan?): Boolean = try {
        attempt()?.let { error("A probe must not acquire a lock: $it") }
        false
    } catch (_: Cache.CacheException) { true }

    private fun waitUntil(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done()) { check(System.nanoTime() < until) { "Timed out" }; Thread.sleep(5) }
    }

    /** An in-memory upstream for CacheDataSource; no network. */
    private class Bytes(private val data: ByteArray) : DataSource {
        private var position = 0
        private var uri: Uri? = null
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { uri = dataSpec.uri; position = dataSpec.position.toInt(); return (data.size - position).toLong() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= data.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, data.size - position)
            System.arraycopy(data, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri? = uri
        override fun close() { uri = null }
    }
}
