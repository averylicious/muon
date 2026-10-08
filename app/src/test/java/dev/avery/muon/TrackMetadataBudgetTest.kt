package dev.avery.muon

import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import androidx.media3.common.BundleListRetriever
import androidx.media3.common.C
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TrackMetadataBudgetTest {
    private val endpoint = ServerEndpoint.parse("http://192.168.1.10:7814")
    private fun song(title: String) = TauonTrack(42, title, "Artist", "Album", 180_000, true, false)

    @Test fun exactRecordBudgetPreservesTagsAndActualTransportHasHeadroom() {
        val track = song("A".repeat(TRACK_METADATA_MAX_BYTES - encodeSong(song("")).size))
        requireTrackMetadataBudget(track)
        assertEquals(TRACK_METADATA_MAX_BYTES, encodeSong(track).size)
        assertEquals(track, decodeSong(encodeSong(track)))
        val item = track.mediaItem(endpoint)
        val retriever = BundleListRetriever(listOf(item.toBundle()))
        val method = BundleListRetriever::class.java.getDeclaredMethod("onTransact",
            Int::class.javaPrimitiveType, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val requestParcel = Parcel.obtain()
        val reply = Parcel.obtain()
        val intentParcel = Parcel.obtain()
        try {
            requestParcel.writeInt(0); requestParcel.setDataPosition(0)
            assertEquals(true, method.invoke(retriever, IBinder.FIRST_CALL_TRANSACTION, requestParcel, reply, 0))
            assertTrue(reply.dataSize() < C.SUGGESTED_MAX_IPC_SIZE)
            val id = downloadId(endpoint.origin, track.id)
            val download = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/${track.id}")))
                .setCustomCacheKey(id).setData(encodeSong(track)).build()
            DownloadService.buildAddDownloadIntent(RuntimeEnvironment.getApplication(),
                MuonDownloadService::class.java, download, false).writeToParcel(intentParcel, 0)
            assertTrue(intentParcel.dataSize() < C.SUGGESTED_MAX_IPC_SIZE)
            println("MUON_ACCEPTED_METADATA recordBytes=${encodeSong(track).size} replyBytes=${reply.dataSize()} intentBytes=${intentParcel.dataSize()}")
        } finally { requestParcel.recycle(); reply.recycle(); intentParcel.recycle() }
        val error = assertThrows(IOException::class.java) { requireTrackMetadataBudget(track.copy(title = track.title + "A")) }
        assertTrue(error.message.orEmpty().contains("Check its tags"))
    }

    @Test fun nulSafeCodecExpansionCannotBypassTheIncomingRecordBudget() {
        val accepted = song("Title\u0000part").copy(artist = "宇多田\u0000Guest", album = "Album\u0000edition")
        requireTrackMetadataBudget(accepted)
        val item = accepted.mediaItem(endpoint)
        assertEquals(accepted, decodeSong(requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))))
        val ordinaryLimit = song("A".repeat(TRACK_METADATA_MAX_BYTES - encodeSong(song("")).size))
        assertEquals(TRACK_METADATA_MAX_BYTES, encodeSong(ordinaryLimit).size)
        // Same character count, but #248's safe encoding expands this record beyond #279's byte cap.
        val expanded = ordinaryLimit.copy(title = "\u0000" + ordinaryLimit.title.drop(1))
        assertEquals(ordinaryLimit.title.length, expanded.title.length)
        assertTrue(encodeSong(expanded).size > TRACK_METADATA_MAX_BYTES)
        assertEquals(expanded, decodeSong(encodeSong(expanded)))
        assertThrows(IOException::class.java) { requireTrackMetadataBudget(expanded) }
    }

    @Test fun byteBudgetIncludesUnicodeAndAllFieldsWithoutTruncatingThem() {
        val unicode = song("é".repeat(TRACK_METADATA_MAX_BYTES / 2))
        assertTrue(unicode.title.length < TRACK_METADATA_MAX_BYTES)
        assertThrows(IOException::class.java) { requireTrackMetadataBudget(unicode) }
        val combined = song("A".repeat(6_000)).copy(artist = "B".repeat(6_000), album = "C".repeat(6_000))
        assertThrows(IOException::class.java) { requireTrackMetadataBudget(combined) }
        val ordinary = song("宇多田ヒカル 🎵 Björk").copy(artist = "Guest; Artist", albumArtist = "Various Artists", trackNumber = "3/12")
        requireTrackMetadataBudget(ordinary)
        assertEquals(ordinary, decodeSong(encodeSong(ordinary)))
        assertEquals(ordinary.title, ordinary.mediaItem(endpoint).mediaMetadata.title.toString())
    }

    @Test fun formattedCreditsCannotBypassTheDisplayBudget() {
        val credits = (1..1_000).joinToString(";") { "Guest$it" }
        val base = song("").copy(artist = credits)
        val track = base.copy(title = "A".repeat(TRACK_METADATA_MAX_BYTES - encodeSong(base).size))
        assertEquals(TRACK_METADATA_MAX_BYTES, encodeSong(track).size)
        assertThrows(IOException::class.java) { requireTrackMetadataBudget(track) }
    }

    @Test fun publicApiRejectsUnsafeTrackAndRetryReturnsCompleteUnchangedTags() {
        // Real HTTP/JSON ingestion; still under the existing 16 MiB body limit.
        val bad = "{\"tracks\":[{\"id\":42,\"title\":\"${"L".repeat(700_000)}\"}]}"
        val good = "{\"tracks\":[{\"id\":42,\"title\":\"Ordinary title\",\"artist\":\"Artist\",\"album\":\"Album\",\"duration\":180000,\"can_download\":true}]}"
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 5_000
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            for (json in listOf(bad, good)) socket.accept().use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                assertEquals("GET /api1/tracklist/1 HTTP/1.1", input.readLine())
                while (!input.readLine().isNullOrEmpty()) { }
                val body = json.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(body); flush()
                }
            }
        }
        try {
            val api = TauonApi(ServerEndpoint.parse("http://127.0.0.1:${socket.localPort}"))
            val error = assertThrows(IOException::class.java) { runBlocking { withContext(Dispatchers.IO) { api.tracks("1") } } }
            assertTrue(error.message.orEmpty().contains("16 KiB"))
            val old = listOf(song("Previously loaded"))
            val retained = combineLoad(listOf(TauonPlaylist("1", "Fixture", 1)), emptyMap(), mapOf("1" to old))
            assertSame(old, retained.tracks["1"])
            assertEquals(1, retained.failed)
            val retried = runBlocking { withContext(Dispatchers.IO) { api.tracks("1") } }
            assertEquals(listOf(song("Ordinary title")), retried)
            task.get(5, TimeUnit.SECONDS)
        } finally { socket.close(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
