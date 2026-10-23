package dev.avery.muon

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * #179 S3 PROTOTYPE, test-only: a test-local admission gate around the real DefaultDownloaderFactory,
 * with the real DownloadManager, ProgressiveDownloader, CacheWriter, SimpleCache and native SQLite in
 * disposable folders. Only the upstream is synthetic (a read that blocks through interrupts until the test
 * lets it return; not Muon's OkHttp source). It shows a proposed downloader-admission contract: release,
 * close admission, wait for admitted invocations, then capture. It gates downloader writes only, nothing else,
 * and is not production code or a fix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadDrainPrototypeTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private lateinit var gate: AdmissionGate
    // Kept open until every admitted invocation has returned: ProgressiveDownloader may still submit to it.
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val bytes = ByteArray(1000) { (it % 251).toByte() }
    private val upstream = BlockingUpstream(bytes)
    private val id = "http://192.168.1.20:7814/7"
    private val request = DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7"))
        .setCustomCacheKey(id).build()

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder("card"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database, "drain_prototype")
        gate = AdmissionGate(DefaultDownloaderFactory(
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory { upstream }, executor))
        manager = DownloadManager(RuntimeEnvironment.getApplication(), index, gate)
        manager.setRequirements(Requirements(0)) // No network requirement: nothing here touches a network.
    }

    @After fun tearDown() {
        // Unblock and drain every worker before the cache and database go, even after a failed assertion.
        var drained = false
        var managerStopped = false
        try {
            gate.close()
            upstream.proceed.countDown()
            // release() waits on the manager's internal thread; only call it once that thread has shown it
            // runs (a read was reached), so a harness failure cannot hang teardown.
            if (upstream.entered.count == 0L) {
                managerStopped = runCatching { manager.release() }.isSuccess
            }
            drained = gate.awaitDrained(TimeUnit.SECONDS.toNanos(5))
            if (drained) {
                executor.shutdown()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
            gate.lastThread?.join(TimeUnit.SECONDS.toMillis(5))
        } finally {
            // A failed drain is a fixture failure, never permission to close resources a worker may use.
            if (managerStopped && drained && executor.isTerminated && gate.lastThread?.isAlive != true) {
                try { cache.release() } finally { database.close() }
            }
        }
    }

    @Test fun closedAdmissionDrainsTheAdmittedRealDownloadBeforeCaptureAndRefusesLateWork() {
        manager.resumeDownloads()
        manager.addDownload(request)
        assertTrue("The admitted real CacheWriter reached the upstream read", upstream.entered.await(5, TimeUnit.SECONDS))

        // The fixture submits no further manager commands. Stop the manager before gate refusals
        // become possible: a live manager treats a refused remove as a finished index removal.
        manager.release()
        gate.close()

        // Not drained: the admitted invocation is still inside the real ProgressiveDownloader.
        assertFalse(gate.drained())
        assertEquals("The read is still blocked", 1L, upstream.returned.count)
        // A bounded wait that runs out grants nothing: no capture is taken.
        var early: Snapshot? = null
        if (gate.awaitDrained(TimeUnit.MILLISECONDS.toNanos(100))) early = snapshot()
        assertNull("A timeout is not permission to capture", early)

        // Let the read return; only confirmed completion of every admitted invocation allows the capture.
        upstream.proceed.countDown()
        assertTrue("Every admitted invocation returned", gate.awaitDrained(TimeUnit.SECONDS.toNanos(5)))
        val captured = snapshot()
        println("MUON_DRAIN_PROTOTYPE captured=$captured")
        // Consistent with the merged release-boundary control: the late write committed before drain ended.
        assertEquals(listOf(0L to bytes.size.toLong()), captured.spans)
        assertEquals(bytes.toList(), captured.bytes)
        assertEquals(bytes.size.toLong(), captured.contentLength)

        // Late work after closure, old or new, is refused before any real downloader or cache is touched.
        val admitted = requireNotNull(gate.lastAdmitted)
        assertThrows(AdmissionClosed::class.java) { admitted.remove() }
        val late = gate.createDownloader(request)
        assertThrows(AdmissionClosed::class.java) { late.download(null) }
        assertThrows(AdmissionClosed::class.java) { late.remove() }
        assertEquals("Only the admitted download ever reached a real downloader", 1, gate.created.get())
        assertEquals(3, gate.refused.get())
        assertEquals("Refused late work changed no bytes, spans or content length", captured, snapshot())
    }

    @Test fun aLiveManagerDropsTheIndexAfterARefusedRemovalEvenThoughBytesRemain() =
        exerciseLiveRemoval(refuse = true)

    @Test fun anAdmittedRemovalDropsBothIndexAndBytes() = exerciseLiveRemoval(refuse = false)

    /** A downloader-only refusal is not an index-preservation barrier for a live manager. */
    private fun exerciseLiveRemoval(refuse: Boolean) {
        pumpUntil { manager.isInitialized }
        manager.resumeDownloads()
        manager.addDownload(request)
        assertTrue("The actual downloader reached the disposable source", upstream.entered.await(5, TimeUnit.SECONDS))
        upstream.proceed.countDown()
        pumpUntil { index.getDownload(id)?.state == Download.STATE_COMPLETED && manager.isIdle }
        val before = snapshot()
        assertEquals(bytes.toList(), before.bytes)
        assertTrue(cache.isCached(id, 0, bytes.size.toLong()))

        val removed = AtomicBoolean()
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                if (download.request.id == id) removed.set(true)
            }
        })
        if (refuse) gate.close()
        manager.removeDownload(id)
        pumpUntil { removed.get() && manager.isIdle }

        assertNull("Real manager removal completion dropped the index row", index.getDownload(id))
        if (refuse) {
            assertEquals("The gate actually refused the remover", 1, gate.refused.get())
            assertEquals("Refused removal left every span, byte and length intact", before, snapshot())
        } else {
            assertEquals(0, gate.refused.get())
            assertTrue("Positive control: admitted removal deleted actual cache spans", cache.getCachedSpans(id).isEmpty())
            assertFalse(cache.isCached(id, 0, bytes.size.toLong()))
        }
    }

    private fun pumpUntil(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("The real manager did not reach the expected state within five seconds")
    }

    private data class Snapshot(val spans: List<Pair<Long, Long>>, val bytes: List<Byte>, val contentLength: Long)

    private fun snapshot(): Snapshot {
        val spans = cache.getCachedSpans(id).sortedBy { it.position }
        return Snapshot(spans.map { it.position to it.length },
            spans.fold(byteArrayOf()) { acc, span -> acc + requireNotNull(span.file).readBytes() }.toList(),
            ContentMetadata.getContentLength(cache.getContentMetadata(id)))
    }

    private class AdmissionClosed : CancellationException("Download admission closed")

    /**
     * TEST-LOCAL proposed contract: once closed, no downloader invocation is admitted and nothing reaches
     * the real factory or cache; every admitted invocation is counted until the real downloader returns.
     */
    private class AdmissionGate(private val delegate: DownloaderFactory) : DownloaderFactory {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private var open = true
        private var active = 0
        val created = AtomicInteger()
        val refused = AtomicInteger()
        @Volatile var lastAdmitted: Downloader? = null
        @Volatile var lastThread: Thread? = null

        override fun createDownloader(request: DownloadRequest): Downloader = Gated(request)

        fun close() = lock.withLock { open = false }

        fun drained(): Boolean = lock.withLock { active == 0 }

        /** True only when every admitted invocation has returned; a timeout returns false and grants nothing. */
        fun awaitDrained(timeoutNanos: Long): Boolean = lock.withLock {
            check(!open) { "Admission must close before a drain receipt is requested" }
            var left = timeoutNanos
            while (active > 0) {
                if (left <= 0) return false
                left = idle.awaitNanos(left)
            }
            true
        }

        private fun admit() = lock.withLock {
            if (!open) { refused.incrementAndGet(); throw AdmissionClosed() }
            active++
        }

        private fun leave() = lock.withLock { if (--active == 0) idle.signalAll() }

        private inner class Gated(private val request: DownloadRequest) : Downloader {
            @Volatile private var real: Downloader? = null
            @Volatile private var canceled = false

            override fun download(progressListener: Downloader.ProgressListener?) = admitted {
                lastAdmitted = this
                lastThread = Thread.currentThread()
                it.download(progressListener)
            }

            override fun remove() = admitted { it.remove() }

            override fun cancel() {
                canceled = true
                real?.cancel()
            }

            private fun admitted(work: (Downloader) -> Unit) {
                admit()
                try {
                    val target = real ?: delegate.createDownloader(request).also { real = it; created.incrementAndGet() }
                    // Refuse a cancel that preceded delegate entry instead of invoking an already
                    // canceled ProgressiveDownloader whose runnable may never have been allocated.
                    if (canceled) throw AdmissionClosed()
                    work(target)
                } finally { leave() }
            }
        }
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
