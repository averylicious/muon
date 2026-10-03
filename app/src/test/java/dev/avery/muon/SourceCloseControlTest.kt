package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
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
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * #179 characterization, not a production gate: the actual OfflineDataSource -> Shelf.source
 * CacheDataSource -> real FileDataSource over disposable, fully cached bytes and native SQLite. The only
 * test seam is Shelf.source's cache-read factory, wrapped to inject faults or hold close; its upstream
 * fails if ever reached, so nothing touches a network. The manager stays idle with no downloads.
 * Injected faults are synthetic: they do not show how real file, cache or network failures behave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SourceCloseControlTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private lateinit var shelf: Shelf
    private val endpoint = ServerEndpoint.parse("http://192.168.1.10:7814")
    private val track = TauonTrack(42, "Song", "Artist", "Album", 180000, true, false)
    private val payload = byteArrayOf(1, 2, 3, 4)
    private val id get() = downloadId(endpoint.origin, track.id)

    private enum class Fault { None, OpenAfterDelegate, HoldClose, CloseAfterDelegate }
    @Volatile private var fault = Fault.None
    private val wrappers = CopyOnWriteArrayList<Wrapper>()
    private val upstreamOpens = AtomicInteger()
    private val closeGate = CountDownLatch(1)
    private val worker = Executors.newSingleThreadExecutor()

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder("phone"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database)
        // No download is ever added: the manager only supplies its actual index to Shelf.
        manager = DownloadManager(RuntimeEnvironment.getApplication(), index,
            DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache),
                java.util.concurrent.Executor { it.run() }))
        shelf = Shelf(cache, manager, MuonDownloadService::class.java)
        // The only seams: the cache-read source (a real FileDataSource, wrapped) and an upstream that
        // fails if ever reached, replacing the OkHttp one before any source is created.
        shelf.source.setCacheReadDataSourceFactory { Wrapper(FileDataSource()).also { wrappers.add(it) } }
        shelf.source.setUpstreamDataSourceFactory { FailingUpstream() }
        seedCompletedDownload()
    }

    @After fun tearDown() {
        // Open every gate, then attempt every wait, before any assertion or resource release.
        closeGate.countDown()
        worker.shutdown()
        val drained = worker.awaitTermination(5, TimeUnit.SECONDS)
        if (drained) {
            try { manager.release(); cache.release() } finally { database.close() }
        }
        assertTrue("The test worker must finish before the cache and database close", drained)
        assertEquals("The upstream must never be reached", 0, upstreamOpens.get())
    }

    @Test fun openFailureAfterTheRealFileOpenedLeavesCloseToTheCaller() {
        fault = Fault.OpenAfterDelegate
        val source = offlineSource()
        assertThrows(IOException::class.java) { source.open(stream()) }
        val wrapper = wrappers.single()
        assertEquals("The real FileDataSource opened before the injected failure", 1, wrapper.delegateOpens.get())
        assertEquals("The failed open did not close it", 0, wrapper.delegateCloses.get())

        source.close()
        assertEquals("The caller's close reached the real FileDataSource once", 1, wrapper.delegateCloses.get())
        assertNull("OfflineDataSource cleared its active source", source.uri)
        source.close()
        assertEquals("A second close does nothing more", 1, wrapper.closeCalls.get())
        assertEquals(1, wrapper.delegateCloses.get())
    }

    @Test fun aHeldCloseHasNoCompletionReceiptAndUriIsAlreadyCleared() {
        fault = Fault.HoldClose
        val owner = AtomicReference<OfflineDataSource>()
        val uriDuringClose = AtomicReference<Uri?>(Uri.EMPTY)
        val closeEntered = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        // One worker owns the whole lifecycle; the test thread only watches latches, counters and the future.
        val lifecycle: Future<ByteArray> = worker.submit<ByteArray> {
            val source = offlineSource().also(owner::set)
            source.open(stream())
            val read = readAll(source)
            holdHook = {
                uriDuringClose.set(owner.get().uri) // Same worker, inside close: no concurrent source call.
                closeEntered.countDown()
            }
            try { source.close() } finally { closeReturned.countDown() }
            read
        }

        assertTrue("The worker reached the held close", closeEntered.await(5, TimeUnit.SECONDS))
        val wrapper = wrappers.single()
        assertEquals("No close-completion receipt while the close is held", 1L, closeReturned.count)
        assertEquals("The real FileDataSource is not closed yet", 0, wrapper.delegateCloses.get())
        assertNull("CacheDataSource cleared its URI before the delegate close finished", uriDuringClose.get())

        closeGate.countDown()
        assertArrayEquals(payload, lifecycle.get(5, TimeUnit.SECONDS))
        assertEquals(0L, closeReturned.count)
        assertEquals(1, wrapper.delegateCloses.get())
    }

    @Test fun closeFailureAfterTheRealCloseStillClearsTheActiveSource() {
        fault = Fault.CloseAfterDelegate
        val source = offlineSource()
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        val wrapper = wrappers.single()

        assertThrows(IOException::class.java) { source.close() }
        assertEquals("The real FileDataSource closed before the injected failure", 1, wrapper.delegateCloses.get())
        assertNull("OfflineDataSource cleared its active source despite the failure", source.uri)
        source.close()
        assertEquals("A later close does not retry that source", 1, wrapper.closeCalls.get())
    }

    @Volatile private var holdHook: () -> Unit = {}

    private fun offlineSource() = OfflineDataSource { request -> routeOfflineRequest(request, shelf, listOf(shelf), false) }

    private fun stream(): DataSpec =
        DataSpec.Builder().setUri(requireNotNull(track.mediaItem(endpoint).localConfiguration).uri).build()

    private fun readAll(source: DataSource): ByteArray {
        var bytes = byteArrayOf()
        val buffer = ByteArray(64)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return bytes
            bytes += buffer.copyOf(count)
        }
    }

    private fun seedCompletedDownload() {
        val hole = cache.startReadWrite(id, 0L, payload.size.toLong())
        try {
            val file = cache.startFile(id, 0L, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
            cache.applyContentMetadataMutations(id, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            })
        } finally { cache.releaseHoleSpan(hole) }
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/42")))
            .setCustomCacheKey(id).setData(encodeSong(track)).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 0L, 0L, payload.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    /** The real FileDataSource, with a synthetic fault or hold added around it. */
    private inner class Wrapper(private val delegate: FileDataSource) : DataSource {
        val delegateOpens = AtomicInteger()
        val delegateCloses = AtomicInteger()
        val closeCalls = AtomicInteger()

        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val length = delegate.open(dataSpec)
            delegateOpens.incrementAndGet()
            if (fault == Fault.OpenAfterDelegate) throw IOException("Injected after FileDataSource.open")
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun close() {
            closeCalls.incrementAndGet()
            if (fault == Fault.HoldClose) {
                holdHook()
                closeGate.await() // Only the test or teardown opens it; no timeout unblocks the premise.
            }
            delegate.close()
            delegateCloses.incrementAndGet()
            if (fault == Fault.CloseAfterDelegate) throw IOException("Injected after FileDataSource.close")
        }
    }

    private inner class FailingUpstream : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            upstreamOpens.incrementAndGet()
            throw IOException("No upstream in this fixture")
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("No upstream")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }
}
