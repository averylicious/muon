package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #179 S3: the pinned DownloadManager's release contract with a real manager, index and native SQLite.
 * The downloader is a controlled stand-in for one whose cancellation is asynchronous or blocked in I/O:
 * it ignores cancel and interrupt until the test lets it finish. This shows what release() waits for; it
 * does not show Muon's real ProgressiveDownloader still writing, or any data loss, after release.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadReleaseBoundaryTest {
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private val downloader = BlockingDownloader()
    private val id = "http://192.168.1.20:7814/7"

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        index = DefaultDownloadIndex(database, "release_boundary")
        manager = DownloadManager(RuntimeEnvironment.getApplication(), index, DownloaderFactory { downloader })
        manager.setRequirements(Requirements(0)) // No network requirement: nothing here touches a network.
    }

    @After fun tearDown() {
        // Always unblock and drain the worker before the database goes, even after a failed assertion.
        try {
            downloader.finish.countDown()
            // release() waits on the manager's internal thread; only call it once that thread has shown
            // it runs (it started the task), so a harness failure cannot hang teardown.
            if (downloader.started.count == 0L) runCatching { manager.release() }
            downloader.worker?.join(TimeUnit.SECONDS.toMillis(5))
            assertFalse("Download thread must be drained before teardown", downloader.worker?.isAlive == true)
        } finally {
            database.close()
        }
    }

    @Test fun releaseReturnsAfterRequestingCancellationWithoutWaitingForTheDownloadThread() {
        manager.resumeDownloads()
        manager.addDownload(DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7")).build())
        assertTrue("The manager's task thread entered download()", downloader.started.await(5, TimeUnit.SECONDS))
        val worker = requireNotNull(downloader.worker)

        manager.release()

        // release() asked for cancellation and persisted the index...
        assertEquals("release() called Downloader.cancel()", 0L, downloader.cancelled.count)
        assertEquals(Download.STATE_QUEUED, index.getDownload(id)?.state)
        // ...but returned while the download thread is still inside download().
        assertTrue(worker.isAlive)
        assertEquals("download() has not returned", 1L, downloader.returned.count)

        // Only the downloader itself letting go ends the thread; the test drains it explicitly.
        downloader.finish.countDown()
        worker.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(worker.isAlive)
        assertEquals(0L, downloader.returned.count)
    }

    /**
     * Stays inside download() until [finish] opens, through cancel() and interrupts, for at most a
     * bounded time so a failed test cannot hang. Not a model of any real Media3 downloader's timing.
     */
    private class BlockingDownloader : Downloader {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val returned = CountDownLatch(1)
        @Volatile var worker: Thread? = null

        override fun download(progressListener: Downloader.ProgressListener?) {
            worker = Thread.currentThread()
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var interrupted = false
            try {
                while (true) {
                    val left = deadline - System.nanoTime()
                    check(left > 0) { "The test never let the download finish" }
                    try {
                        if (finish.await(left, TimeUnit.NANOSECONDS)) break
                    } catch (e: InterruptedException) {
                        interrupted = true // Ignored on purpose: this downloader doesn't stop on interrupt.
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
                returned.countDown()
            }
        }

        override fun cancel() { cancelled.countDown() }

        override fun remove() = Unit
    }
}
