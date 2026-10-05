package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** #213 characterization: the mismatch assertions describe the existing bug, NOT an identity fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RetainedIdentityCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private lateinit var shelf: Shelf
    private val endpoint = ServerEndpoint.parse("http://192.168.1.10:7814")
    private val old = TauonTrack(42, "Retained A", "Artist A", "Album A", 180000, true, false)
    private val replacement = old.copy(title = "Live B", artist = "Artist B", album = "Album B")
    private val payloadA = byteArrayOf(1, 2, 3, 4)
    private val id get() = downloadId(endpoint.origin, old.id)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder("phone"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database)
        // No downloader is started; the manager only supplies its actual download index to Shelf.
        manager = DownloadManager(RuntimeEnvironment.getApplication(), index,
            DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache),
                java.util.concurrent.Executor { it.run() }))
        shelf = Shelf(cache, manager, MuonDownloadService::class.java)
    }

    @After fun tearDown() {
        try { manager.release(); cache.release() } finally { database.close() }
    }

    @Test fun liveReplacementStillReadsExplicitRetainedAudioAndIndexNamesOldSong() {
        downloadOldSong()
        val item = replacement.mediaItem(endpoint)
        assertEquals(replacement, decodeSong(requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))))
        assertNull("No current song identity reaches the progressive DataSpec", item.localConfiguration?.customCacheKey)
        val spec = DataSpec.Builder().setUri(requireNotNull(item.localConfiguration).uri).build()
        val routed = routeOfflineRequest(spec, shelf, listOf(shelf), offline = false)
        assertSame(shelf, routed.first)
        assertEquals(id, routed.second.key)
        assertEquals(old, decodeSong(requireNotNull(index.getDownload(id)).request.data))
        // The production OfflineDataSource reads the actual retained spans, without network access.
        val source = OfflineDataSource { request -> routeOfflineRequest(request, shelf, listOf(shelf), false) }
        try {
            assertEquals(payloadA.size.toLong(), source.open(spec))
            val read = ByteArray(payloadA.size)
            assertEquals(payloadA.size, source.read(read, 0, read.size))
            assertArrayEquals(payloadA, read)
        } finally { source.close() }
    }

    @Test fun ordinaryMatchingDownloadUsesTheSameRetainedRoute() {
        downloadOldSong()
        val result = routeOfflineRequest(stream(old), shelf, listOf(shelf), offline = false)
        assertEquals(id, result.second.key)
        assertEquals(endpoint.url("/api1/fileopus/42"), result.second.uri.toString())
        assertTrue(shelf.completed(id))
    }

    @Test fun offlinePlayedCopyAlsoSelectsOldBytesForAReusedLiveId() {
        seed(playedKey(id), encodeSong(old))
        val result = routeOfflineRequest(stream(replacement), shelf, listOf(shelf), offline = true)
        assertEquals(playedKey(id), result.second.key)
        assertEquals(old, decodeSong(requireNotNull(cache.getContentMetadata(playedKey(id))
            .get(SONG_METADATA, null as ByteArray?))))
        assertTrue(hasPlayedCopy(cache, id))
    }

    @Test fun onlinePlayedCopyAndDifferentOriginDoNotRedirect() {
        seed(playedKey(id), encodeSong(old))
        val spec = stream(replacement)
        assertSame(spec, routeOfflineRequest(spec, shelf, listOf(shelf), offline = false).second)
        downloadOldSong()
        val other = DataSpec.Builder().setUri("http://192.168.1.11:7814/api1/file/42").build()
        assertSame(other, routeOfflineRequest(other, shelf, listOf(shelf), offline = true).second)
    }

    @Test fun identicalTagsDoNotEstablishThatExplicitSavedBytesAreTheLiveAudio() =
        identicalTagsStillSelectSavedBytes(explicit = true)

    @Test fun identicalTagsDoNotEstablishThatOfflinePlayedBytesAreTheLiveAudio() =
        identicalTagsStillSelectSavedBytes(explicit = false)

    @Test fun bundleRoundTripsPreserveMetadataButDoNotAlwaysPreserveLocalRoutingFields() {
        // Test-local marker, not an implemented or trusted production saved-entry schema.
        val markerKey = "test.saved-entry"
        val savedKey = "test-retained-key"
        val plain = old.mediaItem(endpoint)
        val metadata = plain.mediaMetadata.buildUpon().setExtras(android.os.Bundle(plain.mediaMetadata.extras).apply {
            putString(markerKey, "test-unverified-entry")
        }).build()
        val item = plain.buildUpon().setCustomCacheKey(savedKey).setTag(Any()).setMediaMetadata(metadata).build()
        val withLocal = androidx.media3.common.MediaItem.fromBundle(item.toBundleIncludeLocalConfiguration())
        assertEquals(savedKey, requireNotNull(withLocal.localConfiguration).customCacheKey)
        assertNull("LocalConfiguration tag is deliberately omitted even when URI/key are included",
            withLocal.localConfiguration?.tag)
        val timelineForm = androidx.media3.common.MediaItem.fromBundle(item.toBundle())
        assertNull("Ordinary MediaItem bundles omit local URI, cache key and tag", timelineForm.localConfiguration)
        for (restored in listOf(withLocal, timelineForm)) {
            assertEquals(item.mediaId, restored.mediaId)
            assertEquals("test-unverified-entry", restored.mediaMetadata.extras?.getString(markerKey))
            assertArrayEquals(encodeSong(old), requireNotNull(restored.mediaMetadata.extras?.getByteArray(SONG_EXTRA)))
        }
        // The current Undo helper still reconstructs a live stream URL from this shared numeric ID.
        assertEquals(endpoint.url("/api1/file/${old.id}"), restoreUrl(timelineForm.mediaId))
    }

    /** A byte fixture, not playable encoded audio or a live Tauon rebuild. No user/network data. */
    private fun identicalTagsStillSelectSavedBytes(explicit: Boolean) {
        val liveBytes = byteArrayOf(9, 8, 7, 6)
        val requests = AtomicInteger()
        val peerFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { peer ->
            peer.soTimeout = 10_000
            val local = ServerEndpoint.parse("http://127.0.0.1:${peer.localPort}")
            val localId = downloadId(local.origin, old.id)
            val savedKey = if (explicit) localId else playedKey(localId)
            val item = old.mediaItem(local) // Same complete tags/ID; the peer serves different bytes.
            assertArrayEquals(encodeSong(old), requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA)))
            seed(savedKey, encodeSong(old))
            if (explicit) {
                val request = DownloadRequest.Builder(localId, Uri.parse(local.url("/api1/fileopus/${old.id}")))
                    .setCustomCacheKey(localId).setData(encodeSong(old)).build()
                index.putDownload(Download(request, Download.STATE_COMPLETED, 0L, 0L, payloadA.size.toLong(),
                    Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
                assertEquals(old, decodeSong(requireNotNull(index.getDownload(localId)).request.data))
            } else {
                assertEquals(old, decodeSong(requireNotNull(cache.getContentMetadata(savedKey)
                    .get(SONG_METADATA, null as ByteArray?))))
            }
            val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
            val owner = thread(name = "muon-retained-identity-peer", isDaemon = true) {
                try {
                    while (!peer.isClosed) peer.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        check(reader.readLine() == "GET /api1/file/${old.id} HTTP/1.1")
                        while (true) {
                            val header = reader.readLine() ?: error("Incomplete request headers")
                            if (header.isEmpty()) break
                        }
                        requests.incrementAndGet()
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Length: ${liveBytes.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(liveBytes); flush()
                        }
                    }
                } catch (failure: Throwable) {
                    if (!(failure is SocketException && peer.isClosed)) peerFailure.set(failure)
                }
            }
            try {
                // Independent real HTTP control establishes the live endpoint bytes, not tag inference.
                client.newCall(Request.Builder().url(local.url("/api1/file/${old.id}")).build()).execute().use {
                    assertEquals(200, it.code)
                    assertArrayEquals(liveBytes, requireNotNull(it.body).bytes())
                }
                val spec = DataSpec.Builder().setUri(requireNotNull(item.localConfiguration).uri).build()
                val source = OfflineDataSource { request ->
                    routeOfflineRequest(request, shelf, listOf(shelf), offline = !explicit)
                }
                try {
                    assertEquals(payloadA.size.toLong(), source.open(spec))
                    val actual = ByteArray(payloadA.size)
                    assertEquals(actual.size, source.read(actual, 0, actual.size))
                    assertArrayEquals("Current routing still returns saved A, not live B", payloadA, actual)
                    assertFalse(actual.contentEquals(liveBytes))
                    assertEquals(-1, source.read(ByteArray(1), 0, 1))
                    assertEquals("Only the independent live control contacted the peer", 1, requests.get())
                    assertEquals(savedKey, routeOfflineRequest(spec, shelf, listOf(shelf), !explicit).second.key)
                } finally { source.close() }
                assertEquals("Retained bytes remain intact", payloadA.size.toLong(), cache.getCacheSpace())
            } finally {
                peer.close(); owner.join(5000)
                client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                assertTrue(client.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
                assertFalse("Loopback owner must terminate before disposable cache teardown", owner.isAlive)
                assertNull("No unexpected peer failure", peerFailure.get())
            }
        }
    }

    private fun stream(track: TauonTrack): DataSpec =
        DataSpec.Builder().setUri(requireNotNull(track.mediaItem(endpoint).localConfiguration).uri).build()

    private fun downloadOldSong() {
        seed(id, encodeSong(old))
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/42")))
            .setCustomCacheKey(id).setData(encodeSong(old)).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 0L, 0L, payloadA.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun seed(key: String, song: ByteArray) {
        val hole = cache.startReadWrite(key, 0L, payloadA.size.toLong())
        try {
            val file = cache.startFile(key, 0L, payloadA.size.toLong())
            file.writeBytes(payloadA)
            cache.commitFile(file, payloadA.size.toLong())
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(SONG_METADATA, song).apply {
                ContentMetadataMutations.setContentLength(this, payloadA.size.toLong())
            })
        } finally { cache.releaseHoleSpan(hole) }
    }
}
