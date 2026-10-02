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

/** Actual public API/HTTP/platform JSON path; no Compose or phone-runtime claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PlaylistIdentityTest {
    @Test fun distinctIdsPreserveServerOrderAndAllowEqualNames() {
        Response("""{"playlists":[{"id":"2","name":"Same","count":2},{"id":"1","name":"Same","count":1}]}""").use { server ->
            assertEquals(listOf(TauonPlaylist("2", "Same", 2), TauonPlaylist("1", "Same", 1)),
                runBlocking { server.api.playlists() })
            assertEquals("GET /api1/playlists HTTP/1.1", server.requestLine)
        }
    }

    @Test fun duplicatePlaylistIdsAreRejectedEvenWithDifferentNamesOrAnEmptyCount() {
        for (secondCount in listOf(0, 1)) {
            Response("""{"playlists":[{"id":"1","name":"First","count":1},{"id":"1","name":"Other","count":$secondCount}]}""").use { server ->
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { server.api.playlists() }
                }
                assertTrue(failure.message.orEmpty().contains("duplicate playlist identifiers"))
                assertEquals("GET /api1/playlists HTTP/1.1", server.requestLine)
            }
        }
    }

    @Test fun anEmptyPlaylistDirectoryRemainsValid() {
        Response("""{"playlists":[]}""").use { server ->
            assertTrue(runBlocking { server.api.playlists() }.isEmpty())
        }
    }

    @Test fun duplicateTrackOccurrencesInsideAPlaylistRemainValid() {
        Response("""{"tracks":[{"id":7,"title":"Song"},{"id":7,"title":"Song"}]}""").use { server ->
            val tracks = runBlocking { server.api.tracks("1") }
            assertEquals(2, tracks.size)
            assertEquals(tracks[0], tracks[1])
            assertEquals("GET /api1/tracklist/1 HTTP/1.1", server.requestLine)
        }
    }

    private class Response(json: String) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val closing = AtomicBoolean(false)
        @Volatile var requestLine: String? = null
        val api = TauonApi(ServerEndpoint.parse("http://127.0.0.1:${socket.localPort}"))
        private val task = executor.submit {
            try {
                socket.accept().use { client ->
                    client.soTimeout = 5000
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
