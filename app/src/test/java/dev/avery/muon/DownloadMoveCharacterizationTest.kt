package dev.avery.muon

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.DownloaderFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * #230: actual OfflineStore.move/remove calls, real cache copy/indexes, captured service intents.
 * Failure cases assert CURRENT unsafe ordering, not a production fix or Android service delivery.
 * Reflection only installs disposable shelves and waits for the existing mover's FIFO boundary;
 * it does not replace the worker, copy implementation, command sender or main-thread handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadMoveCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private lateinit var sourceIndex: DefaultDownloadIndex
    private lateinit var targetIndex: DefaultDownloadIndex
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val moverField = OfflineStore::class.java.getDeclaredField("mover").apply { isAccessible = true }
    private var previousStore: Any? = null
    private val id = "http://192.168.1.20:7814/7"
    private val request get() = DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7"))
        .setCustomCacheKey(id).setData(byteArrayOf(1, 2, 3)).build()
    private val bytes = ByteArray(512) { (it % 251).toByte() }

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        database = StandaloneDatabaseProvider(app)
        sourceIndex = DefaultDownloadIndex(database, "move_source")
        targetIndex = DefaultDownloadIndex(database, "move_target")
        phone = shelf("phone", sourceIndex, MuonDownloadService::class.java)
        card = shelf("card", targetIndex, MuonCardDownloadService::class.java)
        val prefs = app.getSharedPreferences("move-fixture", Context.MODE_PRIVATE)
        val fixture = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, prefs, database, {}, {})
        fixture.card = card
        previousStore = storeField.get(null)
        storeField.set(null, fixture)
        startedCommands() // Ignore unrelated setup work; no service is instantiated by this fixture.
    }

    @After fun tearDown() {
        try {
            awaitMover()
            shadowOf(Looper.getMainLooper()).idle()
            storeField.set(null, previousStore)
            DownloadMarks.moving = null
            phone.manager.release()
            card.manager.release()
            card.cache.release()
            phone.cache.release()
        } finally {
            database.close()
        }
    }

    @Test fun normalMoveCopiesExactBytesThenPostsOneAddWhileSourceRemains() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        assertTrue(card.cache.isCached(id, 0, bytes.size.toLong()))
        assertArrayEquals(bytes, targetBytes())
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertTrue("Worker posts the add; it has not started a service yet", startedCommands().isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        val add = startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(MuonCardDownloadService::class.java.name, add.component?.className)
        assertEquals(request, addRequest(add))
        assertNull("Captured intents are not end-to-end service delivery", targetIndex.getDownload(id))
    }

    @Test fun removingTheSongBeforeQueuedAddStillEmitsTheLaterAdd() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        OfflineStore.remove(app, listOf(id))
        val removals = startedCommands()
        assertEquals(2, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD &&
            it.getStringExtra(DownloadService.KEY_CONTENT_ID) == id })
        shadowOf(Looper.getMainLooper()).idle()
        val add = startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(id, addRequest(add).id)
        assertArrayEquals(bytes, targetBytes())
    }

    @Test fun removingAllBeforeQueuedAddStillEmitsTheLaterAdd() {
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        OfflineStore.removeAll(app)
        val removals = startedCommands()
        assertEquals(2, removals.size)
        assertTrue(removals.all { it.action == DownloadService.ACTION_REMOVE_ALL_DOWNLOADS })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(id, addRequest(startedCommands().single {
            it.action == DownloadService.ACTION_ADD_DOWNLOAD }).id)
    }

    @Test fun missingLaterSpanLeavesPartialDestinationBytesWithoutAnAddOrDownloadRecord() {
        val chunk = ByteArray(128 * 1024) { (it % 251).toByte() }
        seed(phone.cache, 0, chunk)
        val later = seed(phone.cache, chunk.size.toLong(), chunk)
        setLength(phone.cache, 2L * chunk.size)
        putCompleted(2L * chunk.size)
        assertTrue(phone.cache.isCached(id, 0, 2L * chunk.size))
        assertTrue(later.delete()) // Only a disposable fixture file, before the actual worker reads it.
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        assertTrue("Failed copy retains unindexed target spans", card.cache.getCachedSpans(id).isNotEmpty())
        assertFalse(card.cache.isCached(id, 0, 2L * chunk.size))
        assertArrayEquals(chunk, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
    }

    // Unsafe characterization, not a fix: the move reuses whatever the target already holds for the
    // key. Shows the copy's reuse only, not DownloadManager adoption, decoding or a renumbered song.
    @Test fun unindexedTargetPrefixIsKeptAndCompletedFromTheSourceThenAdded() {
        val old = ByteArray(200) { (255 - it % 251).toByte() } // Differs from [bytes] at every index.
        seed(card.cache, 0, old)
        completeSource(bytes)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        assertTrue(card.cache.isCached(id, 0, bytes.size.toLong()))
        assertArrayEquals(old + bytes.copyOfRange(old.size, bytes.size), targetBytes())
        assertEquals(bytes.size.toLong(), ContentMetadata.getContentLength(card.cache.getContentMetadata(id)))
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
        shadowOf(Looper.getMainLooper()).idle()
        val add = startedCommands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(MuonCardDownloadService::class.java.name, add.component?.className)
        assertEquals(request, addRequest(add))
    }

    @Test fun failedMoveOverAnUnindexedTargetPrefixKeepsItAndTheSourceWithoutAnAdd() {
        val old = ByteArray(1000) { (255 - it % 251).toByte() }
        seed(card.cache, 0, old)
        val chunk = ByteArray(128 * 1024) { (it % 251).toByte() }
        seed(phone.cache, 0, chunk)
        val later = seed(phone.cache, chunk.size.toLong(), chunk)
        setLength(phone.cache, 2L * chunk.size)
        putCompleted(2L * chunk.size)
        assertTrue(later.delete()) // Only a disposable fixture file, before the actual worker reads it.
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertNull(targetIndex.getDownload(id))
        val earlier = card.cache.getCachedSpans(id).single { it.position == 0L }
        assertEquals(old.size.toLong(), earlier.length)
        assertArrayEquals(old, requireNotNull(earlier.file).readBytes())
        assertFalse(card.cache.isCached(id, 0, 2L * chunk.size))
        assertArrayEquals(chunk, requireNotNull(phone.cache.getCachedSpans(id).first().file).readBytes())
        assertNotNull(sourceIndex.getDownload(id))
    }

    @Test fun unknownSourceLengthRejectsCopyWithoutRemovingSourceOrAddingDestination() {
        seed(phone.cache, 0, bytes)
        putCompleted(bytes.size.toLong())
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(startedCommands().none { it.action == DownloadService.ACTION_ADD_DOWNLOAD })
        assertTrue(card.cache.getCachedSpans(id).isEmpty())
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertNotNull(sourceIndex.getDownload(id))
    }

    private fun shelf(name: String, index: DefaultDownloadIndex,
        service: Class<out DownloadService>): Shelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val neverDownload = DownloaderFactory { error("Fixture must not start a downloader/network") }
        return Shelf(cache, DownloadManager(app, index, neverDownload), service)
    }

    private fun completeSource(payload: ByteArray) {
        seed(phone.cache, 0, payload)
        setLength(phone.cache, payload.size.toLong())
        putCompleted(payload.size.toLong())
    }

    private fun putCompleted(length: Long) {
        sourceIndex.putDownload(Download(request, Download.STATE_COMPLETED, 1, 1, length,
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun seed(cache: SimpleCache, position: Long, payload: ByteArray): File {
        val hole = requireNotNull(cache.startReadWrite(id, position, payload.size.toLong()))
        try {
            val file = cache.startFile(id, position, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
            return file
        } finally { cache.releaseHoleSpan(hole) }
    }

    private fun setLength(cache: SimpleCache, length: Long) {
        cache.applyContentMetadataMutations(id, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, length)
        })
    }

    private fun targetBytes(): ByteArray = card.cache.getCachedSpans(id)
        .sortedBy { it.position }.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() }

    @Suppress("DEPRECATION")
    private fun addRequest(intent: Intent): DownloadRequest =
        requireNotNull(intent.getParcelableExtra(DownloadService.KEY_DOWNLOAD_REQUEST))

    private fun startedCommands(): List<Intent> = buildList {
        val shadow = shadowOf(app)
        while (true) add(shadow.nextStartedService ?: break)
    }

    private fun awaitMover() {
        val mover = moverField.get(null) as ExecutorService
        mover.submit {}.get(10, TimeUnit.SECONDS)
    }
}
