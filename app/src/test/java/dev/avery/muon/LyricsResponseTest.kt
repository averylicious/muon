package dev.avery.muon

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** Real public lyrics endpoint and platform JSON; no user-server or Compose execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LyricsResponseTest {
    @Test fun validStringTextIsReturnedUnchangedIncludingUnicodeAndLiteralNull() {
        for (wanted in listOf("one\n二\n\uD83C\uDFB5", "null")) {
            Response(JSONObject().put("lyrics_text", wanted).toString()).use { server ->
                assertEquals(wanted, runBlocking { server.api.lyrics(7) })
                assertEquals("GET /api1/lyrics/7 HTTP/1.1", server.requestLine)
            }
        }
    }

    @Test fun missingAndEmptyTextRemainNoLyrics() {
        for (response in listOf("{}", "{\"lyrics_text\":\"\"}")) {
            Response(response).use { server -> assertEquals("", runBlocking { server.api.lyrics(7) }) }
        }
    }

    @Test fun jsonNullDoesNotBecomeFabricatedLyrics() {
        val response = "{\"lyrics_text\":null}"
        assertEquals("null", JSONObject(response).optString("lyrics_text"))
        Response(response).use { server -> assertEquals("", runBlocking { server.api.lyrics(7) }) }
    }

    @Test fun objectsAndArraysAreRejectedWithoutStringifyingTheirContent() {
        val marker = "PRIVATE_LYRICS_MARKER" + "x".repeat(64 * 1024)
        for (response in listOf(JSONObject().put("lyrics_text", JSONObject().put("value", marker)).toString(),
                JSONObject().put("lyrics_text", org.json.JSONArray().put(marker)).toString())) {
            // Existing platform optString coerces the whole value, rather than validating text.
            assertTrue(JSONObject(response).optString("lyrics_text").contains(marker))
            Response(response).use { server ->
                val failure = assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { server.api.lyrics(7) }
                }
                assertTrue(friendlyError(failure).contains("retry"))
                assertTrue(friendlyError(failure).length < 512)
                assertFalse(friendlyError(failure).contains("PRIVATE_LYRICS_MARKER"))
            }
        }
    }

    @Test fun numericAndBooleanValuesAreNotLyricsText() {
        for (value in listOf("42", "true")) {
            Response("{\"lyrics_text\":$value}").use { server ->
                assertThrows(IllegalArgumentException::class.java) { runBlocking { server.api.lyrics(7) } }
            }
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
