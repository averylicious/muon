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
 * Tests document the CURRENT obsolete callback publication. They do not render Compose UI or
 * instantiate a DownloadService, and do not imply real-device timing or actual download resurrection.
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
    }

    @Test fun oldSnapshotPublishesCompletedAfterActualManagerRemoval() {
        startSnapshot()
        manager.removeDownload(victim)
        pumpUntil { events.any { it == Event(victim, null) } }
        assertNull(index.getDownload(victim))
        assertTrue(cache.getCachedSpans(victim).isEmpty())
        val removalPosition = events.indexOfLast { it == Event(victim, null) }
        index.release.countDown()
        awaitSnapshot()
        assertTrue("Current bug: obsolete completed record follows newer removal",
            events.indexOfLast { it == Event(victim, Download.STATE_COMPLETED) } > removalPosition)
        assertNull("The actual index is still removed: this is stale UI publication", index.getDownload(victim))
        assertTrue(cache.getCachedSpans(victim).isEmpty())
        assertEquals(Download.STATE_COMPLETED, index.getDownload(control)?.state)
        assertTrue(cache.isCached(control, 0, payload.size.toLong()))
    }

    @Test fun oldSnapshotPublishesCompletedAfterActualManagerStateChange() {
        startSnapshot()
        manager.addDownload(request(victim), 42)
        pumpUntil { events.any { it == Event(victim, Download.STATE_STOPPED) } }
        assertEquals(Download.STATE_STOPPED, index.getDownload(victim)?.state)
        index.release.countDown()
        awaitSnapshot()
        assertEquals("Current bug: snapshot replaces the newer callback state",
            Event(victim, Download.STATE_COMPLETED), events.last { it.id == victim })
        assertEquals(Download.STATE_STOPPED, index.getDownload(victim)?.state)
        assertEquals(42, index.getDownload(victim)?.stopReason)
        assertTrue(cache.isCached(control, 0, payload.size.toLong()))
    }

    private fun startSnapshot() {
        val shelf = Shelf(cache, manager, MuonDownloadService::class.java)
        val fixture = OfflineStore.Store(shelf, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("bootstrap-fixture", Context.MODE_PRIVATE), database,
            { events += Event(it.request.id, it.state) }, { events += Event(it.request.id, null) })
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
        .setCustomCacheKey(id).build()

    private fun completed(id: String, time: Long) {
        val hole = requireNotNull(cache.startReadWrite(id, 0, payload.size.toLong()))
        try {
            val file = cache.startFile(id, 0, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(id,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        index.putDownload(Download(request(id), Download.STATE_COMPLETED, time, time,
            payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
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
