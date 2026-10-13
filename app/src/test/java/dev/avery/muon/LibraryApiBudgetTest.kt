package dev.avery.muon

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LibraryApiBudgetTest {
    @Test fun publicTracksApiRejectsWideArrayThenRetriesWithoutDroppingTags() {
        val bad = "{\"tracks\":[" + (0..50_000).joinToString(",") { "{\"id\":$it}" } + "]}"
        val good = "{\"tracks\":[{\"id\":42,\"title\":\"Complete title\",\"artist\":\"Artist\",\"album\":\"Album\",\"duration\":180000,\"can_download\":true}]}"
        serve("/api1/tracklist/1", listOf(bad, good)) { api ->
            assertThrows(LibraryResourceLimit::class.java) { runBlocking { api.tracks("1") } }
            assertEquals(listOf(TauonTrack(42, "Complete title", "Artist", "Album", 180_000, true, false)),
                runBlocking { api.tracks("1") })
        }
    }

    @Test fun publicPlaylistsApiRejectsWideArrayThenRetriesInServerOrder() {
        val bad = "{\"playlists\":[" + (0..2_048).joinToString(",") { "{\"id\":\"$it\",\"name\":\"List\",\"count\":0}" } + "]}"
        val good = "{\"playlists\":[{\"id\":\"2\",\"name\":\"Second\",\"count\":0},{\"id\":\"1\",\"name\":\"First\",\"count\":1}]}"
        serve("/api1/playlists", listOf(bad, good)) { api ->
            assertThrows(LibraryResourceLimit::class.java) { runBlocking { api.playlists() } }
            assertEquals(listOf(TauonPlaylist("2", "Second", 0), TauonPlaylist("1", "First", 1)),
                runBlocking { api.playlists() })
        }
    }

    private fun serve(path: String, replies: List<String>, check: (TauonApi) -> Unit) {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 5_000
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            for (json in replies) socket.accept().use { client ->
                client.soTimeout = 5_000
                val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                assertEquals("GET $path HTTP/1.1", input.readLine())
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
            check(api)
            task.get(5, TimeUnit.SECONDS)
        } finally { socket.close(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
