package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.io.File

/**
 * Characterizes #179 with the real pinned Media3 implementation and a phone-side database.
 * Renaming a disposable directory models inaccessible card files, NOT Android mount events.
 * The loss cases deliberately assert the known unsafe outcome; passing is NOT proof of a fix.
 * Keep these upstream-behavior tests alongside separate preservation tests for the eventual fix.
 */
@RunWith(RobolectricTestRunner::class)
// No app UI/resources needed. An explicit supported SDK also keeps this runnable on CI's JDK 17.
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RemovableCacheCharacterizationTest {
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

    @Test fun mountedReleaseAndReopenPreserveDownloadedBytes() {
        val folder = folders.newFolder("card")
        val cache = open(folder)
        val file = seed(cache)
        val uid = cache.uid
        cache.release()

        val reopened = open(folder)
        assertEquals(uid, reopened.uid)
        assertReadable(reopened)
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun disappearanceWithoutCacheOperationsPreservesBytesOnReturn() {
        val folder = folders.newFolder("card")
        val cache = open(folder)
        val file = seed(cache)
        val parked = disappear(folder)
        assertArrayEquals(payload, File(parked, file.relativeTo(folder).path).readBytes())
        restore(parked, folder)
        cache.release()

        assertReadable(open(folder))
        assertArrayEquals(payload, file.readBytes())
    }

    @Test fun releaseWhileAbsentOrphansIntactBytesWhichReopenDeletes() {
        val folder = folders.newFolder("card")
        val cache = open(folder)
        val file = seed(cache)
        val uid = cache.uid
        val parked = disappear(folder)

        // A tempting teardown is itself destructive to the phone-side content index.
        cache.release()
        assertArrayEquals(payload, File(parked, file.relativeTo(folder).path).readBytes())
        restore(parked, folder)
        assertArrayEquals(payload, file.readBytes())

        val reopened = open(folder)
        assertEquals(uid, reopened.uid)
        assertFalse(reopened.isCached(key, 0, payload.size.toLong()))
        assertEquals(0L, reopened.cacheSpace)
        assertFalse("Returning orphaned span is deleted during initialization", file.exists())
    }

    @Test fun missingSpanLookupThenMetadataStoreOrphansBytesEvenAfterCardReturns() {
        val folder = folders.newFolder("card")
        val cache = open(folder)
        val file = seed(cache)
        val parked = disappear(folder)

        val hole = requireNotNull(cache.startReadWriteNonBlocking(key, 0, payload.size.toLong()))
        assertFalse(hole.isCached)
        cache.releaseHoleSpan(hole)
        assertEquals(0L, cache.cacheSpace)
        // The actual bytes still exist; only their original path was temporarily inaccessible.
        restore(parked, folder)
        assertArrayEquals(payload, file.readBytes())

        // This ordinary cache operation persists the removal queued by the missing-span lookup.
        // There are no active holes and the directory is present when release runs below.
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, payload.size.toLong())
        })
        cache.release()

        val reopened = open(folder)
        assertFalse(reopened.isCached(key, 0, payload.size.toLong()))
        assertFalse("Restoring files alone did not restore their content ID mapping", file.exists())
    }

    @Test fun healthyPublicSpanSnapshotImportsIntactOrphansIntoAFreshCacheWithoutReopeningTheOldDirectory() {
        val folder = folders.newFolder("card")
        val original = open(folder)
        val oldFile = seed(original)
        val uid = original.uid
        // These are public immutable span fields. No filename parsing or private table/ID edits.
        val span = original.getCachedSpans(key).single()
        val length = ContentMetadata.getContentLength(original.getContentMetadata(key))
        val parked = disappear(folder)
        original.release() // Models the same unsafe index purge as the original loss test.
        restore(parked, folder)
        assertArrayEquals(payload, oldFile.readBytes())

        // Deliberately never reopen the old cache directory: that would delete these orphaned files.
        val recovered = open(folders.newFolder("recovered"))
        assertNotEquals(uid, recovered.uid)
        val hole = recovered.startReadWrite(span.key, span.position, span.length)
        assertFalse(hole.isCached)
        try {
            val freshFile = recovered.startFile(span.key, span.position, span.length)
            requireNotNull(span.file).copyTo(freshFile)
            assertEquals(span.length, freshFile.length())
            recovered.commitFile(freshFile, span.length)
        } finally {
            recovered.releaseHoleSpan(hole)
        }
        recovered.applyContentMetadataMutations(span.key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, length)
        })
        assertReadable(recovered)
        recovered.release()
        assertReadable(open(File(folder.parentFile, "recovered")))
        assertArrayEquals("Original file must remain intact", payload, oldFile.readBytes())
    }

    // Main's phone shelf: database index plus PlayedSongEvictor, which requests touches but never evicts
    // a non-played key. A read replaces the CacheSpan object but keeps its file.
    @Test fun databaseBackedTouchReplacesTheSpanButKeepsItsFile() {
        val cache = open(folders.newFolder("phone"), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val file = seed(cache)
        val snapshot = SpanSnapshot(cache.getCachedSpans(key).single())
        cache.addListener(key, snapshot)
        val captured = snapshot.span

        val read = requireNotNull(cache.startReadWriteNonBlocking(key, 0, payload.size.toLong()))
        assertTrue(read.isCached)
        val (old, new) = snapshot.touches.single()
        assertSame(captured, old)
        assertSame(read, new)
        assertNotSame(old, new)
        assertEquals(file, old.file)
        assertEquals("Database-backed touch keeps the path", file, new.file)
        assertSame(new, snapshot.span)
        assertSame(new, cache.getCachedSpans(key).single())
        assertArrayEquals(payload, file.readBytes())
    }

    // Control only: main never builds a cache without a database. Without a file index, a touch
    // renames the file, so a captured path goes stale and only a listener can follow it.
    @Config(instrumentedPackages = ["androidx.media3.datasource.cache"])
    @Test fun legacyIndexTouchRenamesTheFileAndAListenerFollowsIt() {
        val cache = SimpleCache(folders.newFolder("legacy"), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            null, null, false, false).also { caches += it; it.checkInitialization() }
        val file = seed(cache)
        val snapshot = SpanSnapshot(cache.getCachedSpans(key).single())
        cache.addListener(key, snapshot)
        val captured = snapshot.span
        // Instrument just this dependency package so System.currentTimeMillis calls use Robolectric.
        assertEquals(android.os.SystemClock.uptimeMillis(), captured.lastTouchTimestamp)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))

        val read = requireNotNull(cache.startReadWriteNonBlocking(key, 0, payload.size.toLong()))
        val (old, new) = snapshot.touches.single()
        assertSame(captured, old)
        assertSame(read, new)
        assertEquals(file, old.file)
        assertTrue(new.lastTouchTimestamp > captured.lastTouchTimestamp)
        assertNotEquals("Legacy touch moves the bytes to a new name", file, new.file)
        assertFalse("The captured path no longer names the bytes", file.exists())
        assertSame(new, snapshot.span)
        assertArrayEquals(payload, requireNotNull(snapshot.span.file).readBytes())
    }

    @Test fun losingCardIndexDoesNotErasePhoneCacheInTheSharedDatabase() {
        val phoneFolder = folders.newFolder("phone")
        val phone = open(phoneFolder)
        val phoneFile = seed(phone)
        val folder = folders.newFolder("card")
        val card = open(folder)
        val cardFile = seed(card)
        assertNotEquals(phone.uid, card.uid)
        val parked = disappear(folder)
        card.release()
        restore(parked, folder)

        assertFalse(open(folder).isCached(key, 0, payload.size.toLong()))
        assertFalse(cardFile.exists())
        phone.release()
        assertReadable(open(phoneFolder))
        assertArrayEquals(payload, phoneFile.readBytes())
    }

    private fun open(folder: File, evictor: CacheEvictor = NoOpCacheEvictor()): SimpleCache =
        SimpleCache(folder, evictor, database).also {
            caches += it
            it.checkInitialization()
        }

    /** A snapshot of one span that follows touches through the public listener; test memory only. */
    private class SpanSnapshot(var span: CacheSpan) : Cache.Listener {
        val touches = mutableListOf<Pair<CacheSpan, CacheSpan>>()
        override fun onSpanAdded(cache: Cache, span: CacheSpan) = Unit
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            touches += oldSpan to newSpan
            if (oldSpan === span) span = newSpan
        }
    }

    private fun seed(cache: SimpleCache): File {
        val hole = cache.startReadWrite(key, 0, payload.size.toLong())
        assertFalse(hole.isCached)
        try {
            val file = cache.startFile(key, 0, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            })
            return file
        } finally {
            cache.releaseHoleSpan(hole)
        }
    }

    private fun assertReadable(cache: SimpleCache) {
        assertTrue(cache.isCached(key, 0, payload.size.toLong()))
        val span = requireNotNull(cache.startReadWriteNonBlocking(key, 0, payload.size.toLong()))
        assertTrue(span.isCached)
        assertArrayEquals(payload, requireNotNull(span.file).readBytes())
    }

    private fun disappear(folder: File): File {
        val parked = File(folder.parentFile, "absent-card")
        assertTrue("Test must make the original cache path inaccessible", folder.renameTo(parked))
        assertFalse(folder.exists())
        return parked
    }

    private fun restore(parked: File, folder: File) {
        assertFalse(folder.exists())
        assertTrue("Test must restore exactly the original directory", parked.renameTo(folder))
    }
}
