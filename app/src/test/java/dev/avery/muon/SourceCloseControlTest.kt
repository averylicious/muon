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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val sources = CopyOnWriteArrayList<OfflineDataSource>()
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
        // Open every gate and drain the worker first, so no source is ever called from two threads.
        closeGate.countDown()
        worker.shutdown()
        val drained = worker.awaitTermination(5, TimeUnit.SECONDS)
        // Then close every source a test made, even after a failed assertion. Only the deliberately
        // injected failure thrown after a successful real close is expected; anything else fails the fixture.
        val cleanupFailures = ArrayList<Throwable>()
        if (drained) {
            for (source in sources) {
                try { source.close() }
                catch (expected: InjectedAfterClose) { }
                catch (failure: Throwable) { cleanupFailures += failure }
            }
        }
        val openedDelegatesClosed = wrappers.all { it.delegateOpens.get() == 0 || it.delegateCloses.get() >= 1 }
        // Dependencies are released only when every real file was closed; otherwise they are retained.
        if (drained && cleanupFailures.isEmpty() && openedDelegatesClosed) {
            try { manager.release(); cache.release() } finally { database.close() }
        }
        assertTrue("The test worker must finish before any source is closed or the cache released", drained)
        assertEquals("Unexpected source cleanup failures", emptyList<Throwable>(), cleanupFailures)
        assertTrue("Every real FileDataSource that opened must have closed", openedDelegatesClosed)
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
            var read: ByteArray? = null
            try {
                source.open(stream())
                read = readAll(source)
            } finally {
                // Closed by this worker whether or not open/read succeeded.
                holdHook = {
                    uriDuringClose.set(owner.get().uri) // Same worker, inside close: no concurrent source call.
                    closeEntered.countDown()
                }
                try { source.close() } finally { closeReturned.countDown() }
            }
            requireNotNull(read)
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

    // ---- #179 reader-admission PROTOTYPE (test-local, not production code or a production gate) ----
    // A per-open lease around the actual OfflineDataSource, for this fixture's one fixed shelf. Teardown
    // never relies on the prototype: it disposes the cache only from the wrappers' independently observed
    // real FileDataSource closes.

    @Test fun closedAdmissionRefusesAPreviouslyMadeSourceBeforeAnyRouteOrCacheWork() {
        val gate = ReaderAdmission()
        val source = admitted(gate) // Made while admission was open; constructing it does no work.
        gate.close()
        assertThrows(ReaderAdmissionClosed::class.java) { source.open(stream()) }
        source.close() // The DataSource contract still asks for close after a failed open.
        assertEquals("No routing ran", 0, routeCalls.get())
        assertTrue("No cache source was made", wrappers.isEmpty())
        assertTrue(gate.drained())
    }

    @Test fun anActiveCachedOpenBlocksDrainUntilItsCloseReturns() {
        val gate = ReaderAdmission()
        val source = admitted(gate)
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        gate.close()
        assertFalse("Closed admission alone is not drained", gate.drained())
        source.close()
        assertEquals(1, wrappers.single().delegateCloses.get())
        assertTrue(gate.drained())
    }

    @Test fun aFailedOpenAfterTheFileOpenedStaysCountedUntilTheCallerCloses() {
        fault = Fault.OpenAfterDelegate
        val gate = ReaderAdmission()
        val source = admitted(gate)
        assertThrows(IOException::class.java) { source.open(stream()) }
        gate.close()
        assertEquals("The failed open still holds its lease", 1, gate.active())
        assertFalse(gate.drained())
        source.close()
        assertEquals(1, wrappers.single().delegateCloses.get())
        assertTrue(gate.drained())
    }

    @Test fun aHeldCloseKeepsDrainFalseUntilTheCloseReturns() {
        fault = Fault.HoldClose
        val gate = ReaderAdmission()
        val closeEntered = CountDownLatch(1)
        // One worker owns the source; the test thread only operates the gate and watches latches.
        val lifecycle: Future<ByteArray> = worker.submit<ByteArray> {
            val source = admitted(gate)
            var read: ByteArray? = null
            try {
                source.open(stream())
                read = readAll(source)
            } finally {
                holdHook = { closeEntered.countDown() }
                source.close()
            }
            requireNotNull(read)
        }
        assertTrue("The worker reached the held close", closeEntered.await(5, TimeUnit.SECONDS))
        gate.close()
        assertFalse("A close in progress is not drained", gate.drained())
        closeGate.countDown()
        assertArrayEquals(payload, lifecycle.get(5, TimeUnit.SECONDS))
        assertTrue("Drained once the close returned", gate.awaitDrained(TimeUnit.SECONDS.toNanos(5)))
    }

    @Test fun aCloseFailureLeavesTheGenerationUncertainEvenAfterALaterNoOpClose() {
        fault = Fault.CloseAfterDelegate
        val gate = ReaderAdmission()
        val source = admitted(gate)
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        assertThrows(InjectedAfterClose::class.java) { source.close() }
        assertTrue(gate.uncertain())
        // Uncertainty refuses new opens even before admission closes, and does no routing.
        assertThrows(ReaderAdmissionClosed::class.java) { source.open(stream()) }
        assertEquals(1, routeCalls.get())
        source.close() // A no-op close must not clear the uncertainty.
        gate.close()
        assertFalse("No drain while uncertain", gate.drained())
        assertFalse(gate.awaitDrained(0))
        // Test cleanup proof only, not a permission from the prototype: the real file did close.
        assertEquals(1, wrappers.single().delegateCloses.get())
    }

    @Test fun aReusedSourceReleasesEachLeaseAndCannotReopenAfterAdmissionCloses() {
        val gate = ReaderAdmission()
        val source = admitted(gate)
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        source.close()
        assertEquals(0, gate.active())
        source.open(stream()) // The same instance, reopened: a new lease and a new underlying source.
        assertEquals(1, gate.active())
        assertEquals(2, wrappers.size)
        assertArrayEquals(payload, readAll(source))
        gate.close()
        assertFalse(gate.drained())
        source.close()
        assertTrue(gate.drained())
        assertThrows(ReaderAdmissionClosed::class.java) { source.open(stream()) }
        source.close()
        assertEquals("The refused reopen made no new source", 2, wrappers.size)
        assertEquals(2, routeCalls.get())
        assertTrue(gate.drained())
    }

    private class ReaderAdmissionClosed : IOException("Reader admission closed or uncertain")

    /**
     * Per-open admission for one fixed shelf generation. Drained only when admission is closed, no
     * lease is held and no close has failed. A failed close quarantines the generation as uncertain.
     */
    private class ReaderAdmission {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private var admitting = true
        private var leases = 0
        private var uncertain = false

        fun close() = lock.withLock { admitting = false }
        fun acquire(): Boolean = lock.withLock { if (!admitting || uncertain) false else { leases++; true } }
        fun release() = lock.withLock {
            check(leases > 0) { "Released more leases than acquired" }
            if (--leases == 0) idle.signalAll()
        }
        fun quarantine() = lock.withLock { uncertain = true; idle.signalAll() }
        fun active(): Int = lock.withLock { leases }
        fun uncertain(): Boolean = lock.withLock { uncertain }
        fun drained(): Boolean = lock.withLock { !admitting && leases == 0 && !uncertain }
        /** False on timeout or uncertainty; neither grants any permission. */
        fun awaitDrained(timeoutNanos: Long): Boolean = lock.withLock {
            var left = timeoutNanos
            while (!(!admitting && leases == 0 && !uncertain)) {
                if (uncertain || left <= 0) return false
                left = idle.awaitNanos(left)
            }
            true
        }
    }

    /** Acquires on every open, before OfflineDataSource routes; releases only after its close returns. */
    private class AdmittedSource(private val gate: ReaderAdmission, private val delegate: OfflineDataSource) : DataSource {
        private var leased = false

        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            check(!leased) { "Open without closing the previous open" }
            if (!gate.acquire()) throw ReaderAdmissionClosed()
            leased = true // Held through a failed open, until the caller's close.
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun close() {
            if (!leased) { delegate.close(); return }
            leased = false
            try { delegate.close() }
            catch (failure: Throwable) { gate.quarantine(); throw failure } // Never released: uncertain.
            gate.release()
        }
    }

    private fun admitted(gate: ReaderAdmission) = AdmittedSource(gate, offlineSource())

    private val routeCalls = AtomicInteger()

    @Volatile private var holdHook: () -> Unit = {}

    /** Every source a test makes is tracked, so teardown can close it after a failure. */
    private fun offlineSource() = OfflineDataSource { request ->
        routeCalls.incrementAndGet()
        routeOfflineRequest(request, shelf, listOf(shelf), false)
    }.also { sources.add(it) }

    private fun stream(): DataSpec =
        DataSpec.Builder().setUri(requireNotNull(track.mediaItem(endpoint).localConfiguration).uri).build()

    /** Bounded to the tiny payload: a source that returns zero forever or too many bytes fails, not hangs. */
    private fun readAll(source: DataSource): ByteArray {
        var bytes = byteArrayOf()
        val buffer = ByteArray(payload.size + 1)
        repeat(payload.size + 2) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return bytes
            check(count > 0) { "read returned $count" }
            bytes += buffer.copyOf(count)
            check(bytes.size <= payload.size) { "More bytes than the fixture holds" }
        }
        error("No end of input within the expected payload")
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
            if (fault == Fault.CloseAfterDelegate) throw InjectedAfterClose()
        }
    }

    /** Thrown only after the real FileDataSource closed successfully. */
    private class InjectedAfterClose : IOException("Injected after FileDataSource.close")

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
