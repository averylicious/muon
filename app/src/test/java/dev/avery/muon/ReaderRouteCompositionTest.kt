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
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * #179 characterization, not a production gate: actual routeOfflineRequest and OfflineDataSource over two
 * disposable shelves (phone, card) with real caches, native-SQLite DefaultDownloadIndexes and FileDataSource
 * reads. Routing consults the phone index before choosing the card, so a per-shelf reader lease must cover
 * every consulted generation, not only the selected one. The admission bundle below is TEST-LOCAL and
 * deliberately conservative; it is not a production design. Each index is wrapped only to record which
 * generation leases were held at every getDownload; upstreams fail if ever reached, so nothing touches a
 * network. Injected faults are synthetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ReaderRouteCompositionTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private val phoneGate = Generation()
    private val cardGate = Generation()
    private val endpoint = ServerEndpoint.parse("http://192.168.1.10:7814")
    private val track = TauonTrack(42, "Song", "Artist", "Album", 180000, true, false)
    private val payload = byteArrayOf(5, 6, 7, 8)
    private val id get() = downloadId(endpoint.origin, track.id)

    private enum class Fault { None, OpenAfterDelegate, HoldClose, CloseAfterDelegate }
    @Volatile private var fault = Fault.None
    @Volatile private var holdHook: () -> Unit = {}
    private val closeGate = CountDownLatch(1)
    private val worker = Executors.newSingleThreadExecutor()
    private val caches = ArrayList<SimpleCache>()
    private val managers = ArrayList<DownloadManager>()
    private val wrappers = CopyOnWriteArrayList<Wrapper>()
    private val sources = CopyOnWriteArrayList<OfflineDataSource>()
    /** Every getDownload: which index, and whether that index's generation lease was held at the time. */
    private val queries = CopyOnWriteArrayList<Pair<String, Boolean>>()
    private val routeCalls = AtomicInteger()
    private val upstreamOpens = AtomicInteger()

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        phone = shelf("phone", "phone_index", phoneGate)
        // The card's completed record goes into its real index table before its manager exists; the phone
        // index has no record of this song, so routing must ask both.
        seedCardIndex()
        card = shelf("card", "card_index", cardGate)
        seedCardCache()
    }

    @After fun tearDown() {
        // Open every hold and drain the worker first, so no source is ever called from two threads.
        closeGate.countDown()
        worker.shutdown()
        val drained = worker.awaitTermination(5, TimeUnit.SECONDS)
        // Then close every source a test made, even after a failed assertion. Only the deliberately
        // injected failure thrown after a successful real close is expected.
        val cleanupFailures = ArrayList<Throwable>()
        if (drained) {
            for (source in sources) {
                try { source.close() }
                catch (expected: InjectedAfterClose) { }
                catch (failure: Throwable) { cleanupFailures += failure }
            }
        }
        // Disposal permission comes only from each real FileDataSource's observed close, never from the
        // test-local generation gates.
        val openedDelegatesClosed = wrappers.all { it.delegateOpens.get() == 0 || it.delegateCloses.get() >= 1 }
        if (drained && cleanupFailures.isEmpty() && openedDelegatesClosed) {
            try {
                managers.forEach { it.release() } // No downloader tasks; release waits for the internal release flag, not worker joins.
                caches.forEach { it.release() }
            } finally { database.close() }
        }
        assertTrue("The test worker must finish before any source is closed or the caches released", drained)
        assertEquals("Unexpected source cleanup failures", emptyList<Throwable>(), cleanupFailures)
        assertTrue("Every real FileDataSource that opened must have closed", openedDelegatesClosed)
        assertEquals("The upstream must never be reached", 0, upstreamOpens.get())
    }

    @Test fun aCardHitConsultsThePhoneIndexAndTheCardIndexUnderBothLeases() {
        val source = admitted()
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        // Actual routing asked the phone first (a miss), then the card (a completed hit).
        assertEquals(listOf("phone_index" to true, "card_index" to true), queries)
        assertEquals("The bytes came from the card's cache", "card", wrappers.single().shelf)
        assertEquals(1, phoneGate.active())
        assertEquals(1, cardGate.active())
        source.close()
        assertEquals(1, wrappers.single().delegateCloses.get())
        assertEquals(0, phoneGate.active())
        assertEquals(0, cardGate.active())
    }

    @Test fun aClosedCardGenerationRefusesBeforeAnyRouteQueryOrSourceAndRollsBackThePhoneLease() {
        val source = admitted()
        cardGate.close()
        assertThrows(GenerationClosed::class.java) { source.open(stream()) }
        source.close() // The DataSource contract still asks for close after a failed open.
        assertEquals("No routing ran", 0, routeCalls.get())
        assertTrue("No index was consulted", queries.isEmpty())
        assertTrue("No cache source was made", wrappers.isEmpty())
        assertEquals("The phone lease taken first was rolled back", 0, phoneGate.active())
        phoneGate.close()
        assertTrue(phoneGate.drained())
        assertTrue(cardGate.drained())
    }

    @Test fun anActiveCardReadBlocksBothGenerationsFromDrainingUntilItsCloseReturns() {
        val source = admitted()
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        phoneGate.close(); cardGate.close()
        assertFalse("The phone index was consulted for this open", phoneGate.drained())
        assertFalse("The card copy is still open", cardGate.drained())
        source.close()
        assertEquals(1, wrappers.single().delegateCloses.get())
        assertTrue(phoneGate.drained())
        assertTrue(cardGate.drained())
    }

    @Test fun aFailedCardOpenKeepsBothLeasesUntilTheCallerCloses() {
        fault = Fault.OpenAfterDelegate
        val source = admitted()
        assertThrows(IOException::class.java) { source.open(stream()) }
        phoneGate.close(); cardGate.close()
        assertEquals(1, phoneGate.active())
        assertEquals(1, cardGate.active())
        assertFalse(phoneGate.drained())
        assertFalse(cardGate.drained())
        source.close()
        assertEquals("The caller's close reached the real file", 1, wrappers.single().delegateCloses.get())
        assertTrue(phoneGate.drained())
        assertTrue(cardGate.drained())
    }

    @Test fun aHeldCardCloseKeepsBothGenerationsUndrainedUntilItReturns() {
        fault = Fault.HoldClose
        val closeEntered = CountDownLatch(1)
        // One worker owns the source; the test thread only operates the gates and watches latches.
        val lifecycle: Future<ByteArray> = worker.submit<ByteArray> {
            val source = admitted()
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
        phoneGate.close(); cardGate.close()
        // Purposeful bounded waits while the close is still held: a timeout grants no drain.
        assertFalse(phoneGate.awaitDrained(TimeUnit.MILLISECONDS.toNanos(50)))
        assertFalse(cardGate.awaitDrained(TimeUnit.MILLISECONDS.toNanos(50)))
        closeGate.countDown()
        assertArrayEquals(payload, lifecycle.get(5, TimeUnit.SECONDS))
        assertTrue(phoneGate.awaitDrained(TimeUnit.SECONDS.toNanos(5)))
        assertTrue(cardGate.awaitDrained(TimeUnit.SECONDS.toNanos(5)))
    }

    @Test fun aCardCloseFailureLeavesBothGenerationsUncertainEvenAfterALaterNoOpClose() {
        fault = Fault.CloseAfterDelegate
        val source = admitted()
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        assertThrows(InjectedAfterClose::class.java) { source.close() }
        // Conservative: every generation this open leased is uncertain, not only the selected card.
        assertTrue(phoneGate.uncertain())
        assertTrue(cardGate.uncertain())
        assertThrows(GenerationClosed::class.java) { source.open(stream()) }
        assertEquals("The refused reopen did no routing", 1, routeCalls.get())
        assertEquals("Nor any index query", 2, queries.size)
        source.close() // A no-op close must not clear the uncertainty.
        phoneGate.close(); cardGate.close()
        assertFalse(phoneGate.drained())
        assertFalse(cardGate.awaitDrained(0))
        // Test cleanup proof only, not a permission from the gates: the real file did close.
        assertEquals(1, wrappers.single().delegateCloses.get())
    }

    @Test fun aReusedSourceTakesNewLeasesOnEachOpenAndIsRefusedOnceAGenerationCloses() {
        val source = admitted()
        source.open(stream())
        assertArrayEquals(payload, readAll(source))
        source.close()
        source.open(stream()) // The same instance, reopened: new leases, a new route and a new card source.
        assertEquals(1, phoneGate.active())
        assertEquals(1, cardGate.active())
        assertEquals(2, wrappers.size)
        assertEquals(List(2) { listOf("phone_index" to true, "card_index" to true) }.flatten(), queries)
        assertArrayEquals(payload, readAll(source))
        source.close()
        cardGate.close()
        assertThrows(GenerationClosed::class.java) { source.open(stream()) }
        source.close()
        assertEquals("The refused reopen made no new route or source", 2, routeCalls.get())
        assertEquals(2, wrappers.size)
        assertEquals(0, phoneGate.active())
    }

    // ---- TEST-LOCAL conservative lease bundle (not production code or a production gate) ----

    private class GenerationClosed : IOException("Reader admission closed or uncertain")

    /** One shelf generation's per-open readers; drained only when closed, unleased and certain. */
    private class Generation {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private var admitting = true
        private var leases = 0
        private var uncertain = false

        fun close() = lock.withLock { admitting = false; idle.signalAll() }
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

    /**
     * Leases every candidate generation, in snapshot order, before the actual OfflineDataSource routes.
     * If a later one refuses, the earlier leases are released and nothing is routed, queried or opened.
     * Conservative: a closed card generation refuses even a request the phone alone could serve.
     */
    private class RouteAdmittedSource(private val generations: List<Generation>, private val delegate: OfflineDataSource) : DataSource {
        private var leased = emptyList<Generation>()

        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            check(leased.isEmpty()) { "Open without closing the previous open" }
            val acquired = ArrayList<Generation>()
            for (generation in generations) {
                if (!generation.acquire()) {
                    acquired.forEach { it.release() } // Roll back before any route, index or source work.
                    throw GenerationClosed()
                }
                acquired += generation
            }
            leased = acquired // Held through a failed open, until the caller's close.
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun close() {
            val held = leased
            if (held.isEmpty()) { delegate.close(); return }
            leased = emptyList()
            try { delegate.close() }
            catch (failure: Throwable) { held.forEach { it.quarantine() }; throw failure } // Never released.
            held.forEach { it.release() }
        }
    }

    private fun admitted() = RouteAdmittedSource(listOf(phoneGate, cardGate), offlineSource())

    /** The actual production routing over both shelves, in the store's order (phone, then card). */
    private fun offlineSource() = OfflineDataSource { request ->
        routeCalls.incrementAndGet()
        routeOfflineRequest(request, phone, listOf(phone, card), false)
    }.also { sources.add(it) }

    // ---- Fixture ----

    /** Delegates every call to the real index; getDownload also records whether its generation was leased. */
    private inner class RecordingIndex(private val name: String, private val generation: Generation,
        private val delegate: DefaultDownloadIndex) : WritableDownloadIndex {
        override fun getDownload(id: String): Download? {
            queries += name to (generation.active() > 0)
            return delegate.getDownload(id)
        }
        override fun getDownloads(vararg states: Int): DownloadCursor = delegate.getDownloads(*states)
        override fun putDownload(download: Download) = delegate.putDownload(download)
        override fun removeDownload(id: String) = delegate.removeDownload(id)
        override fun setDownloadingStatesToQueued() = delegate.setDownloadingStatesToQueued()
        override fun setStatesToRemoving() = delegate.setStatesToRemoving()
        override fun setStopReason(stopReason: Int) = delegate.setStopReason(stopReason)
        override fun setStopReason(id: String, stopReason: Int) = delegate.setStopReason(id, stopReason)
    }

    private fun shelf(folder: String, index: String, generation: Generation): Shelf {
        val cache = SimpleCache(folders.newFolder(folder), NoOpCacheEvictor(), database).also(caches::add)
        cache.checkInitialization()
        // No download is ever added: the manager only supplies its actual index to Shelf.
        val manager = DownloadManager(RuntimeEnvironment.getApplication(),
            RecordingIndex(index, generation, DefaultDownloadIndex(database, index)),
            DownloaderFactory { error("Fixture must not start a downloader") }).also(managers::add)
        return Shelf(cache, manager, MuonDownloadService::class.java).also { shelf ->
            // The only seams: the cache-read source (a real FileDataSource, wrapped) and an upstream
            // that fails if ever reached, replacing the OkHttp one before any source is created.
            shelf.source.setCacheReadDataSourceFactory { Wrapper(folder, FileDataSource()).also { wrappers.add(it) } }
            shelf.source.setUpstreamDataSourceFactory { FailingUpstream() }
        }
    }

    private fun seedCardCache() {
        val hole = card.cache.startReadWrite(id, 0L, payload.size.toLong())
        try {
            val file = card.cache.startFile(id, 0L, payload.size.toLong())
            file.writeBytes(payload)
            card.cache.commitFile(file, payload.size.toLong())
            card.cache.applyContentMetadataMutations(id, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, payload.size.toLong())
            })
        } finally { card.cache.releaseHoleSpan(hole) }
    }

    private fun seedCardIndex() {
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/42")))
            .setCustomCacheKey(id).setData(encodeSong(track)).build()
        DefaultDownloadIndex(database, "card_index").putDownload(Download(request, Download.STATE_COMPLETED, 0L, 0L,
            payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

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

    /** The real FileDataSource, with a synthetic fault or hold added around it. */
    private inner class Wrapper(val shelf: String, private val delegate: FileDataSource) : DataSource {
        val delegateOpens = AtomicInteger()
        val delegateCloses = AtomicInteger()

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
            if (fault == Fault.HoldClose) {
                holdHook()
                closeGate.await() // Only the test or teardown opens it.
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
