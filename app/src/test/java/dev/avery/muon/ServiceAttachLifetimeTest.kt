package dev.avery.muon

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/**
 * The callback Media3's helper posts when a service attaches (DownloadService 984-986, Media3 1.11.0), not
 * a listener callback called by hand. A real manager holds one download in DOWNLOADING through a downloader
 * that waits until cancelled: no audio, network or cache writes. The main looper is paused, so the posted
 * callback runs only when this test drains it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ServiceAttachLifetimeTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var manager: DownloadManager
    private val held = HeldDownloader()
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previousStore: Any? = null

    @Before fun setUp() {
        // Test-only isolation: no other service or production process shares this helper map.
        DownloadService.clearDownloadManagerHelpers()
        val app = RuntimeEnvironment.getApplication()
        database = StandaloneDatabaseProvider(app)
        cache = SimpleCache(folders.newFolder("attach_lifetime"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        manager = DownloadManager(app, DefaultDownloadIndex(database, "attach_lifetime"), DownloaderFactory { held })
        manager.requirements = Requirements(0) // No network requirement to meet in the fixture
        manager.addDownload(DownloadRequest.Builder(ID, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7")).build())
        manager.resumeDownloads()
        awaitDownloading()
        val phone = Shelf(cache, manager, MuonDownloadService::class.java)
        val store = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("attach-lifetime-fixture", Context.MODE_PRIVATE), database, {}, {})
        previousStore = storeField.get(null)
        storeField.set(null, store)
    }

    @After fun tearDown() {
        try {
            storeField.set(null, previousStore)
            // Nothing drains the main looper after this, so no queued update can read the released manager;
            // Robolectric discards the looper's remaining messages when the test ends.
            manager.release() // Cancels the held task, which lets its worker return
            val worker = requireNotNull(held.worker) { "The held download never started" }
            worker.join(TimeUnit.SECONDS.toMillis(10))
            check(!worker.isAlive) { "The actual download task thread did not exit" }
            cache.release()
            DownloadService.clearDownloadManagerHelpers()
        } finally { database.close() }
    }

    @Test fun aLiveServiceRunsTheAttachCallbackAndStopsUpdatingWhenDestroyed() {
        // The controller drains the main looper after onCreate, so the attach callback runs while the instance lives.
        val controller = Robolectric.buildService(MuonDownloadService::class.java).create()
        val service = controller.get()
        val shown = requireNotNull(shadowOf(service).lastForegroundNotification) { "The attach callback started the foreground" }
        assertEquals(PHONE_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals("Built from the manager's current downloads", downloadingTitle(), title(shown))

        val first = notifications().getNotification(PHONE_NOTIFICATION_ID)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(UPDATE_STEP_MS))
        val updated = notifications().getNotification(PHONE_NOTIFICATION_ID)
        assertNotSame("Periodic updates rebuild it while the service lives", first, updated)

        controller.destroy() // onDestroy stops the updater's own handler
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2 * UPDATE_STEP_MS))
        assertSame("No update after a destroy that followed the attach callback", updated,
            notifications().getNotification(PHONE_NOTIFICATION_ID))
    }

    @Test fun anAttachCallbackQueuedBeforeDestroyStillStartsUpdatesOnTheDestroyedInstance() {
        // Artificial ordering: onCreate and onDestroy are called directly, before anything drains the main
        // looper. It shows what the queued callback does if it runs after destroy, not that Android orders it so.
        val service = Robolectric.buildService(MuonDownloadService::class.java).get()
        service.onCreate() // Queues the attach callback at the front of the main queue
        service.onDestroy() // Detaches and clears only the notification updater's handler
        assertNull("Nothing has run yet: the callback is still queued", shadowOf(service).lastForegroundNotification)

        shadowOf(Looper.getMainLooper()).idle()
        val shown = requireNotNull(shadowOf(service).lastForegroundNotification) {
            "The queued callback started the foreground on the destroyed instance"
        }
        assertEquals(PHONE_NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        assertEquals("Built from the manager's current downloads", downloadingTitle(), title(shown))

        val first = notifications().getNotification(PHONE_NOTIFICATION_ID)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(UPDATE_STEP_MS))
        assertNotSame("Periodic updates resumed after destroy, and nothing public stops them", first,
            notifications().getNotification(PHONE_NOTIFICATION_ID))
    }

    private fun awaitDownloading() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (manager.isInitialized && held.started.count == 0L &&
                manager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) return
            check(System.nanoTime() < deadline) { "The download did not become active" }
            Thread.sleep(5)
        }
    }

    private fun notifications() =
        shadowOf(RuntimeEnvironment.getApplication().getSystemService(NotificationManager::class.java))

    private fun downloadingTitle(): String =
        RuntimeEnvironment.getApplication().getString(androidx.media3.exoplayer.R.string.exo_download_downloading)

    private fun title(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    /** Stays in download() until cancelled, so the manager keeps one active task without any I/O. */
    private class HeldDownloader : Downloader {
        val started = CountDownLatch(1)
        @Volatile var worker: Thread? = null
            private set
        private val cancelled = CountDownLatch(1)

        override fun download(progressListener: Downloader.ProgressListener?) {
            worker = Thread.currentThread()
            started.countDown()
            if (!cancelled.await(30, TimeUnit.SECONDS)) throw IOException("Held fixture download was not cancelled")
        }

        override fun cancel() = cancelled.countDown()

        override fun remove() = Unit
    }

    private companion object {
        const val ID = "http://192.168.1.20:7814/7"
        /** MuonDownloadService's notification ID (private in MuonDownloadService.kt). */
        const val PHONE_NOTIFICATION_ID = 2
        /** Just over DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL (1000 ms). */
        const val UPDATE_STEP_MS = 1100L
    }
}
