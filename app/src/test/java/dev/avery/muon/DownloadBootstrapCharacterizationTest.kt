package dev.avery.muon

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.WritableDownloadIndex
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual OfflineStore.watch + Media3 manager/index, with only the bootstrap cursor close gated.
 * Regressions reject obsolete callback publication without changing real index/cache behavior.
 * They do not render Compose UI, instantiate a DownloadService or imply real-device timing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadBootstrapCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: GatedIndex
    private lateinit var manager: DownloadManager
    private val events = ArrayList<Event>() // All callbacks occur on the application/main looper.
    private val victim = "http://127.0.0.1:7814/7"
    private val control = "http://127.0.0.1:7814/8"
    private val payload = ByteArray(128) { it.toByte() }
    private var watching = false

    private data class Event(val id: String, val state: Int?) // null means removed.
    /** Every status the store recorded, in order: what reached the marks, bytes included. */
    private val statuses = ArrayList<DownloadStatus>()

    /** A disposable, non-empty stored song record per row, so the index carries real metadata bytes. */
    private fun metadata(id: String) = encodeSong(TauonTrack(7,
        "Stored $id " + "t".repeat(15 * 1024), "Fixture artist", "Disposable album", 1000, true, false))

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        database = StandaloneDatabaseProvider(app)
        cache = SimpleCache(folders.newFolder("cache"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = GatedIndex(DefaultDownloadIndex(database, "bootstrap_fixture"))
        completed(victim, 1)
        completed(control, 2)
        // Media3 owns removal and its callbacks; the downloader removes actual disposable bytes.
        // No network/downloading is allowed. This boundary does not model codec/progressive removal.
        val removals = DownloaderFactory { request -> object : Downloader {
            override fun download(progressListener: Downloader.ProgressListener?) =
                error("Fixture must never download or reach the network")
            override fun cancel() = Unit
            override fun remove() { cache.removeResource(request.id) }
        } }
        manager = DownloadManager(app, index, removals)
        assertEquals(Looper.getMainLooper(), manager.applicationLooper)
        pumpUntil { manager.isInitialized }
    }

    @After fun tearDown() {
        try {
            index.release.countDown()
            if (watching) awaitSnapshot()
        } finally {
            manager.release()
            cache.release()
            database.close()
        }
    }

    @Test fun unchangedCompletedDownloadsBootstrapWithoutLosingIndexOrBytes() {
        startSnapshot()
        index.release.countDown()
        awaitSnapshot()
        assertEquals(listOf(Event(victim, Download.STATE_COMPLETED), Event(control, Download.STATE_COMPLETED)), events)
        assertEquals(Download.STATE_COMPLETED, index.getDownload(victim)?.state)
        assertTrue(cache.isCached(victim, 0, payload.size.toLong()))
        assertTrue(cache.isCached(control, 0, payload.size.toLong()))
        // #253: the bootstrap publishes each row's exact recorded byte count, and reading the index
        // through it leaves every stored song record exactly as it was.
        assertEquals(listOf(DownloadStatus(victim, Download.STATE_COMPLETED, payload.size.toLong()),
            DownloadStatus(control, Download.STATE_COMPLETED, payload.size.toLong())), statuses)
        for (id in listOf(victim, control)) {
            val row = requireNotNull(index.getDownload(id))
            assertArrayEquals(metadata(id), row.request.data)
            assertEquals(payload.size.toLong(), row.bytesDownloaded)
        }
    }

    @Test fun oldSnapshotCannotPublishCompletedAfterActualManagerRemoval() {
        startSnapshot()
        manager.removeDownload(victim)
        pumpUntil { events.any { it == Event(victim, null) } }
        assertNull(index.getDownload(victim))
        assertTrue(cache.getCachedSpans(victim).isEmpty())
        val removalPosition = events.indexOfLast { it == Event(victim, null) }
        index.release.countDown()
        awaitSnapshot()
        assertFalse("Obsolete completed records must not follow newer removal",
            events.drop(removalPosition + 1).any { it.id == victim })
        assertEquals("The bootstrap published only the unchanged row",
            listOf(DownloadStatus(control, Download.STATE_COMPLETED, payload.size.toLong())),
            statuses.filter { it.state == Download.STATE_COMPLETED })
        assertArrayEquals(metadata(control), index.getDownload(control)?.request?.data)
        assertNull("The actual index is still removed: this is stale UI publication", index.getDownload(victim))
        assertTrue(cache.getCachedSpans(victim).isEmpty())
        assertEquals(Download.STATE_COMPLETED, index.getDownload(control)?.state)
        assertTrue(cache.isCached(control, 0, payload.size.toLong()))
    }

    @Test fun oldSnapshotCannotPublishCompletedAfterActualManagerStateChange() {
        startSnapshot()
        manager.addDownload(request(victim), 42)
        pumpUntil { events.any { it == Event(victim, Download.STATE_STOPPED) } }
        assertEquals(Download.STATE_STOPPED, index.getDownload(victim)?.state)
        index.release.countDown()
        awaitSnapshot()
        assertEquals("Newer live state must survive bootstrap publication",
            Event(victim, Download.STATE_STOPPED), events.last { it.id == victim })
        assertEquals(Download.STATE_STOPPED, index.getDownload(victim)?.state)
        assertEquals(42, index.getDownload(victim)?.stopReason)
        assertTrue(cache.isCached(control, 0, payload.size.toLong()))
    }

    @Test fun liveEventsStillPublishAfterBootstrapTrackingEnds() {
        startSnapshot()
        index.release.countDown()
        awaitSnapshot()
        manager.removeDownload(victim)
        pumpUntil { events.any { it == Event(victim, null) } }
        assertEquals(Event(victim, null), events.last { it.id == victim })
        assertNull(index.getDownload(victim))
        manager.addDownload(request(victim), 42)
        pumpUntil { events.lastOrNull { it.id == victim } == Event(victim, Download.STATE_STOPPED) }
        assertEquals(42, index.getDownload(victim)?.stopReason)
        assertTrue(cache.isCached(control, 0, payload.size.toLong()))
    }

    private fun startSnapshot() {
        val shelf = Shelf(cache, manager, MuonDownloadService::class.java)
        val fixture = OfflineStore.Store(shelf, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("bootstrap-fixture", Context.MODE_PRIVATE), database,
            { statuses += it; events += Event(it.id, it.state) }, { events += Event(it.request.id, null) })
        val watch = OfflineStore::class.java.getDeclaredMethod("watch", Context::class.java,
            Shelf::class.java, OfflineStore.Store::class.java, Handler::class.java).apply { isAccessible = true }
        index.arm.set(true)
        watching = true
        watch.invoke(OfflineStore, app, shelf, fixture, Handler(Looper.getMainLooper()))
        assertTrue("Production bootstrap must capture/close its real index cursor",
            index.captured.await(5, TimeUnit.SECONDS))
    }

    private fun awaitSnapshot() {
        pumpUntil { events.any { it == Event(control, Download.STATE_COMPLETED) } }
    }

    private fun pumpUntil(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(1) // Yield to Media3's real internal handler; not a performance assertion.
        } while (System.nanoTime() < until)
        fail("Timed out waiting for the actual manager/bootstrap main-looper callback")
    }

    private fun request(id: String) = DownloadRequest.Builder(id, Uri.parse("$id.opus"))
        .setCustomCacheKey(id).setData(metadata(id)).build()

    private fun completed(id: String, time: Long) {
        val hole = requireNotNull(cache.startReadWrite(id, 0, payload.size.toLong()))
        try {
            val file = cache.startFile(id, 0, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(id,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        // A recorded byte count, as a finished download has, so the marks' byte total is checkable.
        val progress = androidx.media3.exoplayer.offline.DownloadProgress().apply {
            bytesDownloaded = payload.size.toLong(); percentDownloaded = 100f
        }
        index.putDownload(Download(request(id), Download.STATE_COMPLETED, time, time,
            payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE, progress))
    }

    /** Only holds watch's empty-state bootstrap query, after releasing the real database cursor. */
    private class GatedIndex(private val actual: DefaultDownloadIndex) : WritableDownloadIndex by actual {
        val arm = AtomicBoolean(false)
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun getDownloads(vararg states: Int): DownloadCursor {
            val cursor = actual.getDownloads(*states)
            if (states.isNotEmpty() || !arm.compareAndSet(true, false)) return cursor
            return object : DownloadCursor by cursor {
                override fun close() {
                    cursor.close() // No database lock held while real manager events are allowed.
                    captured.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Bootstrap fixture gate was not released" }
                }
            }
        }
    }
}
