package dev.avery.muon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real public API/OkHttp/Android JSON projection over loopback; no phone or performance assertion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TauonApiProjectionTest {
    @Test fun playlistsRetainValidatedIdsNamesAndCounts() {
        Response("""{"playlists":[{"id":"1","name":"Fixture","count":2},{"id":"2","name":"Empty","count":0}]}""").use { server ->
            val actual = runBlocking { server.api.playlists() }
            assertEquals(listOf(TauonPlaylist("1", "Fixture", 2), TauonPlaylist("2", "Empty", 0)), actual)
            assertEquals("GET /api1/playlists HTTP/1.1", server.requestLine)
        }
    }

    @Test fun tracksKeepFieldsFilenameFallbackAndOptionalTagValidation() {
        Response("""{"tracks":[
          {"id":7,"title":" ","path":"/fixture/Robert Falcon - Heart Of Gold.flac","artist":"Guest Artist","album":"Compilation","duration":180000,"can_download":true,"has_lyrics":true,"album_artist":" Various Artists ","track_number":" 3/12 "},
          {"id":8,"title":"Song","artist":"Björk; 宇多田ヒカル","album_artist":{"name":"invalid"},"track_number":4}
        ]}""").use { server ->
            val actual = runBlocking { server.api.tracks("1") }
            assertEquals(TauonTrack(7, "Robert Falcon - Heart Of Gold.flac", "Guest Artist", "Compilation",
                180000, true, true, "Various Artists", "3/12"), actual[0])
            assertEquals(TauonTrack(8, "Song", "Björk; 宇多田ヒカル", "", 0, false, false,
                "Björk; 宇多田ヒカル", ""), actual[1])
            assertEquals("GET /api1/tracklist/1 HTTP/1.1", server.requestLine)
        }
    }

    @Test fun malformedServerPlaylistIdentifierIsStillRejected() {
        Response("""{"playlists":[{"id":"1/other","name":"Fixture","count":0}]}""").use { server ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { server.api.playlists() } }
        }
    }

    @Test fun negativeServerTrackIdentifierIsStillRejected() {
        Response("""{"tracks":[{"id":-1,"title":"Fixture"}]}""").use { server ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { server.api.tracks("1") } }
        }
    }

    @Test fun malformedRequestedPlaylistIsRejectedBeforeNetworkAccess() {
        Response("""{"tracks":[]}""").use { server ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { server.api.tracks("1/other") } }
            assertFalse(server.received.get())
        }
    }

    @Test fun mergedPublicApiRejectsDeepIgnoredFieldsAfterCancellableRead() {
        val nested = "[".repeat(TAUON_JSON_MAX_DEPTH + 1) + "0" + "]".repeat(TAUON_JSON_MAX_DEPTH + 1)
        Response("{\"tracks\":[],\"ignored\":$nested}").use { server ->
            val failure = assertThrows(java.io.IOException::class.java) { runBlocking { server.api.tracks("1") } }
            assertTrue(failure.message.orEmpty().contains("nesting"))
            assertEquals("GET /api1/tracklist/1 HTTP/1.1", server.requestLine)
        }
    }

    @Test fun actualDiscoveryRequestAcceptsNormalVersionAndRejectsSmallDeepBody() {
        Response("{\"version\":1}").use { server ->
            val origin = server.api.endpoint.origin
            val found = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LanProbe.answer(origin) } }
            assertEquals(DiscoveredServer("Tauon", origin), found)
            assertEquals("GET /api1/version HTTP/1.1", server.requestLine)
        }
        val nested = "[".repeat(TAUON_JSON_MAX_DEPTH + 1) + "0" + "]".repeat(TAUON_JSON_MAX_DEPTH + 1)
        val body = "{\"version\":1,\"ignored\":$nested}"
        assertTrue(body.toByteArray().size < 4096)
        Response(body).use { server ->
            val found = runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LanProbe.answer(server.api.endpoint.origin) } }
            assertNull(found)
        }
    }

    private class Response(json: String) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val closing = AtomicBoolean(false)
        val received = AtomicBoolean(false)
        @Volatile var requestLine: String? = null
        val api = TauonApi(ServerEndpoint.parse("http://127.0.0.1:${socket.localPort}"))
        private val task = executor.submit {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
                    received.set(true)
                    val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                    requestLine = input.readLine()
                    while (true) if (input.readLine().isNullOrEmpty()) break
                    val body = json.toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        write(body)
                        flush()
                    }
                }
            } catch (failure: SocketException) {
                if (!closing.get()) throw failure
            }
        }
        override fun close() {
            closing.set(true)
            socket.close()
            try { task.get(5, TimeUnit.SECONDS) }
            finally { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
        }
    }
}
