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
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException

/**
 * #179 containment in the production OfflineDataSource (docs/audits/2026-10-06-card-reader-containment.md):
 * the actual route, Shelf, CacheDataSource and FileDataSource over disposable cached bytes and native
 * SQLite, for a phone and a card shelf. The card's availability is a switch flipped by the test. That
 * shows what the reader does once [Shelf.available] answers false; it does not establish what a real
 * eject does to files, mounts or reads already under way. No network, device or downloader is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineReaderContainmentTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private val shelves = ArrayList<Shelf>()
    private val sources = ArrayList<OfflineDataSource>()
    private val files = ArrayList<CountingFile>()
    private val upstreams = ArrayList<Upstream>()
    private var cardUp = true
    private var cardChecks = 0
    private val app get() = RuntimeEnvironment.getApplication()
    private val endpoint = ServerEndpoint.parse("http://192.168.1.20:7814")
    private val onCard = TauonTrack(7, "On the card", "Artist", "Album", 180_000, true, false)
    private val onPhone = TauonTrack(8, "On the phone", "Artist", "Album", 180_000, true, false)
    private val notKept = TauonTrack(9, "Streamed", "Artist", "Album", 180_000, true, false)
    private val cardBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    private val phoneBytes = byteArrayOf(11, 12, 13, 14)
    private val streamBytes = byteArrayOf(21, 22, 23)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(app)
        phone = shelf("phone") { true }
        card = shelf("card") { cardChecks++; cardUp }
        complete(card, onCard, cardBytes)
        complete(phone, onPhone, phoneBytes)
    }

    @After fun tearDown() {
        try {
            sources.forEach { runCatching { it.close() } }
            assertTrue("Every real FileDataSource that opened was closed", files.all { it.opens == it.closes })
            assertTrue("Every upstream that opened was closed", upstreams.all { it.opens == it.closes })
        } finally {
            try { shelves.asReversed().forEach { it.manager.release(); it.cache.release() } } finally { database.close() }
        }
    }

    @Test fun aCardThatGoesBetweenReadsFailsTheNextReadWithoutDelegatingIt() {
        val source = offlineSource()
        assertEquals(cardBytes.size.toLong(), source.open(stream(onCard)))
        val file = opened(files).single()
        assertEquals(onCard.routedUri(), source.uri)
        val buffer = ByteArray(4)
        assertEquals(4, source.read(buffer, 0, 4))
        assertArrayEquals(cardBytes.copyOf(4), buffer)
        val reads = file.reads

        cardUp = false
        assertThrows(IOException::class.java) { source.read(buffer, 0, 4) }
        assertEquals("No read reached the card's file after it was found unavailable", reads, file.reads)
        // The card's bytes and index record are untouched: containment closes nothing and deletes nothing.
        assertTrue(card.completed(id(onCard)))
        assertTrue(card.cache.isCached(id(onCard), 0, cardBytes.size.toLong()))

        source.close()
        assertEquals("Close still reached the real file while the card was unavailable", 1, file.closes)
        assertNull(source.uri)
        assertTrue("Nothing was streamed", opened(upstreams).isEmpty())
    }

    @Test fun anInvalidatedOpenStaysInvalidWhenTheCardSeemsToReturn() {
        val source = offlineSource()
        source.open(stream(onCard))
        val file = opened(files).single()
        source.read(ByteArray(4), 0, 4)
        cardUp = false
        assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
        cardUp = true
        val checks = cardChecks
        assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
        assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
        assertEquals("The old reader is not revived, so the card is not even asked again", checks, cardChecks)
        assertEquals(1, file.reads)
        source.close()
        assertEquals(1, file.closes)
    }

    @Test fun afterCloseAFreshOpenRoutesAgainToThePhoneOrTheReturnedCard() {
        val source = offlineSource()
        source.open(stream(onCard))
        source.read(ByteArray(4), 0, 4)
        cardUp = false
        assertThrows(IOException::class.java) { source.read(ByteArray(4), 0, 4) }
        source.close()

        // Still gone: the fresh route streams through the phone shelf; the card's file is not opened again.
        source.open(stream(onCard))
        assertEquals(1, opened(files).size)
        assertArrayEquals(streamBytes, readAll(source))
        assertEquals(1, opened(upstreams).single().opens)
        source.close()

        // Back: a new open reads the card again from the start, through a new reader.
        cardUp = true
        assertEquals(cardBytes.size.toLong(), source.open(stream(onCard)))
        assertArrayEquals(cardBytes, readAll(source))
        assertEquals("A new real file, not the invalidated one", 2, opened(files).size)
        assertEquals(1, opened(files).first().closes)
        source.close()
        assertEquals(1, opened(upstreams).single().opens)
    }

    @Test fun aCardThatGoesBetweenRouteAndSourceCreationIsNeverOpened() {
        val source = OfflineDataSource { request ->
            routeOfflineRequest(request, phone, listOf(phone, card), offline = true).also { cardUp = false }
        }.also { sources += it }
        val routed = routeOfflineRequest(stream(onCard), phone, listOf(phone, card), offline = true)
        assertSame("The route chose the card while it was there", card, routed.first)
        cardUp = true

        assertThrows(IOException::class.java) { source.open(stream(onCard)) }
        assertTrue("No cache source or file was made", files.isEmpty())
        assertTrue("Nothing was streamed in its place", upstreams.isEmpty())
        assertNull(source.uri)
        source.close() // The DataSource contract still asks for close after a failed open.
        assertTrue(card.cache.isCached(id(onCard), 0, cardBytes.size.toLong()))
    }

    @Test fun phoneDownloadsAndStreamsReadAsBeforeWithListenersUriAndHeaders() {
        val listener = Listener()
        val source = offlineSource()
        source.addTransferListener(listener)

        assertEquals(phoneBytes.size.toLong(), source.open(stream(onPhone)))
        val checks = cardChecks
        assertArrayEquals(phoneBytes, readAll(source))
        assertEquals("Reading the phone's copy does not consult the card", checks, cardChecks)
        assertEquals(onPhone.routedUri(), source.uri)
        source.close()
        assertEquals(1, opened(files).single().closes)
        assertTrue("The listener reached the real file source", listener.started >= 1 && listener.ended >= 1)

        source.open(stream(notKept))
        assertArrayEquals(streamBytes, readAll(source))
        assertEquals(Uri.parse(endpoint.url("/api1/file/${notKept.id}")), source.uri)
        assertEquals(listOf("phone"), source.responseHeaders["X-Fixture"])
        val upstream = opened(upstreams).single()
        assertTrue("The listener reached the upstream", upstream.listeners.contains(listener))
        source.close()
        assertEquals(1, upstream.closes)
        assertEquals(emptyMap<String, List<String>>(), source.responseHeaders)
        assertNull(source.uri)
    }

    @Test fun zeroLengthReadsReturnZeroWithoutCheckingOrRevivingALostReader() {
        val source = offlineSource()
        source.open(stream(onCard))
        val file = opened(files).single()
        cardUp = false
        val checks = cardChecks
        assertEquals(0, source.read(ByteArray(0), 0, 0))
        assertEquals(checks, cardChecks)
        assertEquals(0, file.reads)
        assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        cardUp = true
        assertEquals(0, source.read(ByteArray(0), 0, 0))
        assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        source.close()
    }

    @Test fun eachCardReadAsksForAvailabilityOnce() {
        // The cost of containment: one availability query per delegated read of a card copy.
        val source = offlineSource()
        source.open(stream(onCard))
        val before = cardChecks
        repeat(3) { source.read(ByteArray(2), 0, 2) }
        assertEquals(before + 3, cardChecks)
        source.close()
    }

    // ---- fixtures ----

    private fun offlineSource() = OfflineDataSource { request ->
        routeOfflineRequest(request, phone, listOf(phone, card), offline = true)
    }.also { sources += it }

    private fun id(track: TauonTrack) = downloadId(endpoint.origin, track.id)

    // CacheDataSource.Factory makes its file and upstream sources with each source; only opened ones count.
    private fun opened(made: List<CountingFile>) = made.filter { it.opens > 0 }
    @JvmName("openedUpstreams") private fun opened(made: List<Upstream>) = made.filter { it.opens > 0 }

    private fun stream(track: TauonTrack): DataSpec =
        DataSpec.Builder().setUri(Uri.parse(endpoint.url("/api1/file/${track.id}"))).build()

    /** The URI the route asks a shelf for, so the expectation does not restate the route's format. */
    private fun TauonTrack.routedUri(): Uri = routeOfflineRequest(stream(this), phone, listOf(phone, card), true).second.uri

    /** Bounded: a source that never ends, or returns more than any fixture holds, fails rather than hangs. */
    private fun readAll(source: DataSource): ByteArray {
        var bytes = byteArrayOf()
        val buffer = ByteArray(16)
        repeat(20) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return bytes
            check(count > 0) { "read returned $count" }
            bytes += buffer.copyOf(count)
            check(bytes.size <= 16) { "More bytes than any fixture holds" }
        }
        error("No end of input")
    }

    private fun shelf(name: String, present: () -> Boolean): Shelf {
        val cache = SimpleCache(File(folders.root, name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        return Shelf(cache, DownloadManager(app, DefaultDownloadIndex(database, name),
            DownloaderFactory { error("Fixture must not start a downloader/network") }),
            MuonDownloadService::class.java, present).also { shelf ->
            // The only seams: the real FileDataSource, counted, and an in-memory upstream instead of OkHttp.
            shelf.source.setCacheReadDataSourceFactory { CountingFile().also(files::add) }
            shelf.source.setUpstreamDataSourceFactory { Upstream().also(upstreams::add) }
            shelves += shelf
        }
    }

    private fun complete(shelf: Shelf, track: TauonTrack, payload: ByteArray) {
        val id = id(track)
        val hole = shelf.cache.startReadWrite(id, 0, payload.size.toLong())
        try {
            val file = shelf.cache.startFile(id, 0, payload.size.toLong())
            file.writeBytes(payload)
            shelf.cache.commitFile(file, payload.size.toLong())
            shelf.cache.applyContentMetadataMutations(id,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        } finally { shelf.cache.releaseHoleSpan(hole) }
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/${track.id}")))
            .setCustomCacheKey(id).setData(encodeSong(track)).build()
        (shelf.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(request, Download.STATE_COMPLETED,
            1, 1, payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    /** The real FileDataSource, counting what reaches it. */
    private class CountingFile(private val delegate: FileDataSource = FileDataSource()) : DataSource {
        var opens = 0
        var reads = 0
        var closes = 0
        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)
        override fun open(dataSpec: DataSpec): Long = delegate.open(dataSpec).also { opens++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { reads++; return delegate.read(buffer, offset, length) }
        override fun getUri(): Uri? = delegate.uri
        override fun close() {
            val wasOpen = delegate.uri != null
            delegate.close()
            if (wasOpen) closes++
        }
    }

    /** Stands in for Tauon: fixed bytes and one response header, no socket. */
    private inner class Upstream : DataSource {
        val listeners = ArrayList<TransferListener>()
        var opens = 0
        var closes = 0
        private var uri: Uri? = null
        private var position = 0
        override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
        override fun open(dataSpec: DataSpec): Long {
            opens++
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            return (streamBytes.size - position).toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= streamBytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, streamBytes.size - position)
            System.arraycopy(streamBytes, position, buffer, offset, count)
            position += count
            return count
        }
        override fun getUri(): Uri? = uri
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("X-Fixture" to listOf("phone"))
        override fun close() {
            if (uri != null) closes++
            uri = null
        }
    }

    private class Listener : TransferListener {
        var started = 0
        var ended = 0
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { started++ }
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) { ended++ }
    }
}
