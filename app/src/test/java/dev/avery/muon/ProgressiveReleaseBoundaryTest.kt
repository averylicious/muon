package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #179 S3: the real DownloadManager, DefaultDownloaderFactory, ProgressiveDownloader, CacheWriter,
 * CacheDataSource and SimpleCache, with native SQLite and disposable folders. Only the upstream is
 * synthetic: an in-process DataSource whose first read blocks, ignoring interrupts, until the test lets
 * it return. That models a read that doesn't stop on interrupt; it is not Muon's OkHttp source, says
 * nothing about real network timing, and shows no data loss. Test-only; no production change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProgressiveReleaseBoundaryTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bytes = ByteArray(1000) { (it % 251).toByte() }
    private val upstream = BlockingUpstream(bytes)
    @Volatile private var tracked: Tracked? = null
    private val id = "http://192.168.1.20:7814/7"

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder("card"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database, "progressive_boundary")
        val real = DefaultDownloaderFactory(
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { upstream }, executor)
        manager = DownloadManager(RuntimeEnvironment.getApplication(), index,
            DownloaderFactory { request -> Tracked(real.createDownloader(request)).also { tracked = it } })
        manager.setRequirements(Requirements(0)) // No network requirement: nothing here touches a network.
    }

    @After fun tearDown() {
        // Unblock and drain every worker before the cache and database go, even after a failed assertion.
        try {
            upstream.proceed.countDown()
            // release() waits on the manager's internal thread; call it only once that thread has shown it
            // runs (a read was reached), so a harness failure cannot hang teardown.
            if (upstream.entered.count == 0L) runCatching { manager.release() }
            drain()
        } finally {
            try { cache.release() } finally { database.close() }
        }
    }

    @Test fun releaseReturnsWhileTheRealDownloaderIsBlockedAndItsBytesCommitAfterwards() {
        manager.resumeDownloads()
        manager.addDownload(DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7"))
            .setCustomCacheKey(id).build())
        assertTrue("The real CacheWriter reached the upstream read", upstream.entered.await(5, TimeUnit.SECONDS))
        assertTrue("Nothing committed while the read is blocked", cache.getCachedSpans(id).isEmpty())
        println("MUON_RELEASE_BOUNDARY before release: contentLength=" +
            ContentMetadata.getContentLength(cache.getContentMetadata(id)))

        manager.release()

        val task = requireNotNull(tracked?.thread)
        assertEquals("release() returned while the read was still blocked", 1L, upstream.returned.count)
        assertTrue("The manager's task thread is still waiting for the download runnable", task.isAlive)
        assertTrue("Still nothing committed when release() returns", cache.getCachedSpans(id).isEmpty())
        assertEquals(Download.STATE_QUEUED, index.getDownload(id)?.state)

        // The blocked read now returns its bytes; then every worker is drained before looking.
        upstream.proceed.countDown()
        drain()
        assertEquals(0L, upstream.returned.count)

        // Source-derived expectation, first observed by CI: the bytes the read returned are written by
        // TeeDataSource and committed when CacheWriter closes after seeing the cancellation.
        val spans = cache.getCachedSpans(id).sortedBy { it.position }
        println("MUON_RELEASE_BOUNDARY after drain: spans=" + spans.map { it.position to it.length } +
            " contentLength=" + ContentMetadata.getContentLength(cache.getContentMetadata(id)) +
            " index=" + index.getDownload(id)?.state)
        assertEquals("Bytes committed after release() had returned", bytes.size.toLong(), spans.sumOf { it.length })
        assertArrayEquals(bytes, spans.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() })
        assertEquals("The released manager never recorded them", Download.STATE_QUEUED, index.getDownload(id)?.state)
    }

    /** Waits, bounded, for the download runnable's executor and the manager's task thread to end. */
    private fun drain() {
        executor.shutdown()
        assertTrue("Download runnable drained", executor.awaitTermination(5, TimeUnit.SECONDS))
        tracked?.thread?.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("Manager task thread drained", tracked?.thread?.isAlive == true)
    }

    /** The real downloader, unchanged; only records which thread runs download(). */
    private class Tracked(private val real: Downloader) : Downloader {
        @Volatile var thread: Thread? = null
        override fun download(progressListener: Downloader.ProgressListener?) {
            thread = Thread.currentThread()
            real.download(progressListener)
        }
        override fun cancel() = real.cancel()
        override fun remove() = real.remove()
    }

    /**
     * Opens with a known length. Its first read blocks, ignoring interrupts, until [proceed] opens (at most
     * 10 seconds, so a failed test cannot hang), then returns all the bytes; after that, end of input.
     */
    private class BlockingUpstream(private val bytes: ByteArray) : DataSource {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val returned = CountDownLatch(1)
        @Volatile private var uri: Uri? = null
        private var position = 0
        private var blocked = false

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            return (bytes.size - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) return C.RESULT_END_OF_INPUT
            if (!blocked) {
                blocked = true
                entered.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var interrupted = false
                try {
                    while (true) {
                        val left = deadline - System.nanoTime()
                        check(left > 0) { "The test never let the read return" }
                        try {
                            if (proceed.await(left, TimeUnit.NANOSECONDS)) break
                        } catch (e: InterruptedException) {
                            interrupted = true // Ignored on purpose, as a read that doesn't stop on interrupt.
                        }
                    }
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                    returned.countDown()
                }
            }
            val count = minOf(length, bytes.size - position)
            System.arraycopy(bytes, position, buffer, offset, count)
            position += count
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() { uri = null }
    }
}
