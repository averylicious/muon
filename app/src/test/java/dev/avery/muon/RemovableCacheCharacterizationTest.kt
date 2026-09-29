package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
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

    private fun open(folder: File): SimpleCache =
        SimpleCache(folder, NoOpCacheEvictor(), database).also {
            caches += it
            it.checkInitialization()
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
