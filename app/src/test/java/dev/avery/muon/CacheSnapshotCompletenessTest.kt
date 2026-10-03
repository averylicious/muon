package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
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
import java.io.File

/**
 * #179 S3: what a listener-backed or one-time public snapshot cannot see, with the real pinned
 * SimpleCache, native SQLite and disposable folders. Single-threaded and deterministic: no sleeps, mocks,
 * reflection or private tables. These qualify a recovery design's completeness; they fix nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CacheSnapshotCompletenessTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private val caches = mutableListOf<SimpleCache>()
    private val key = "downloaded-song"
    private val payload = ByteArray(256) { it.toByte() }

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() {
        try {
            caches.asReversed().forEach { it.release() }
        } finally {
            database.close()
        }
    }

    @Test fun metadataOnlyChangeReachesNoSpanListenerWhileFreshReadsSeeIt() {
        val cache = open(folders.newFolder("card"))
        val file = commit(cache, 0, payload)
        val old = byteArrayOf(1, 2, 3)
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            set("custom_tag", old)
        })
        // A healthy snapshot: the metadata object as returned, and a copy of every raw entry.
        val retained = cache.getContentMetadata(key)
        val captured = entries(retained)
        val events = Events()
        cache.addListener(key, events)

        val changed = byteArrayOf(9, 9)
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply { set("custom_tag", changed) })

        assertEquals("Cache.Listener has span callbacks only; a metadata change sends none", emptyList<String>(), events.seen)
        val fresh = cache.getContentMetadata(key)
        assertArrayEquals(changed, fresh.get("custom_tag", null as ByteArray?))
        assertArrayEquals("The retained metadata object still shows the old value", old, retained.get("custom_tag", null as ByteArray?))
        assertArrayEquals("So does the copied snapshot", old, captured.getValue("custom_tag"))
        // Bytes and the other metadata are untouched.
        assertEquals(payload.size.toLong(), ContentMetadata.getContentLength(fresh))
        assertTrue(cache.isCached(key, 0, payload.size.toLong()))
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun anAdmittedUncommittedWriteIsMissedByACaptureAndLandsAfterIt() {
        val cache = open(folders.newFolder("card"))
        val half = payload.size / 2
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, payload.size.toLong())
        })
        commit(cache, 0, payload.copyOfRange(0, half))
        val events = Events()
        cache.addListener(key, events)

        // A writer admitted before the capture: it holds the second half's lock and has written its file.
        val hole = cache.startReadWrite(key, half.toLong(), (payload.size - half).toLong())
        assertFalse(hole.isCached)
        try {
            val pending = cache.startFile(key, half.toLong(), (payload.size - half).toLong())
            pending.writeBytes(payload.copyOfRange(half, payload.size))
            // The only public sign of it is a lock on a range the caller already knows to ask about.
            assertNull(cache.startReadWriteNonBlocking(key, half.toLong(), (payload.size - half).toLong()))
    
            // The capture, taken with no new writer admitted after this point.
            val capture = cache.getCachedSpans(key).map { it.position to it.length }
            val capturedBytes = cache.getCachedBytes(key, 0, payload.size.toLong())
            assertEquals(listOf(0L to half.toLong()), capture)
            assertEquals(half.toLong(), capturedBytes)
            assertEquals(emptyList<String>(), events.seen)
    
            // The admitted writer finishes after the capture.
            cache.commitFile(pending, (payload.size - half).toLong())
        } finally {
            cache.releaseHoleSpan(hole)
        }

        assertEquals("Commit is what announces the span", listOf("added:$half:${payload.size - half}"), events.seen)
        assertTrue(cache.isCached(key, 0, payload.size.toLong()))
        // A fresh recapture sees both spans and the exact bytes.
        val recapture = cache.getCachedSpans(key).sortedBy { it.position }
        assertEquals(listOf(0L to half.toLong(), half.toLong() to (payload.size - half).toLong()),
            recapture.map { it.position to it.length })
        assertArrayEquals(payload, recapture.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() })
    }

    /** Every span event for the key, in order; metadata has no callback to record. */
    private class Events : Cache.Listener {
        val seen = mutableListOf<String>()
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { seen += "added:${span.position}:${span.length}" }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { seen += "removed:${span.position}:${span.length}" }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            seen += "touched:${oldSpan.position}:${oldSpan.length}"
        }
    }

    /** Pinned Media3 1.11.0 returns DefaultContentMetadata; the interface itself promises no enumeration. */
    private fun entries(metadata: ContentMetadata): Map<String, ByteArray> =
        (metadata as DefaultContentMetadata).entrySet().associate { it.key to it.value.copyOf() }

    private fun open(folder: File): SimpleCache =
        SimpleCache(folder, NoOpCacheEvictor(), database).also {
            caches += it
            it.checkInitialization()
        }

    private fun commit(cache: SimpleCache, position: Long, bytes: ByteArray): File {
        val hole = cache.startReadWrite(key, position, bytes.size.toLong())
        assertFalse(hole.isCached)
        try {
            val file = cache.startFile(key, position, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            return file
        } finally { cache.releaseHoleSpan(hole) }
    }
}
