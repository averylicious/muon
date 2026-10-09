package dev.avery.muon

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the real merged fetch/decode route, not a substitute identity or HTTP implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ArtworkRequestIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val disk get() = ArtworkStore.disk(context)

    @After fun clearArtwork() {
        ArtworkIdentities.publish(emptyMap())
        forgetArtwork(disk)
    }

    @Test fun lateBodyCannotPublishOldIdentityAndReplacementIsThenCached() = runBlocking(Dispatchers.IO) {
        forgetArtwork(disk)
        HeldArtworkServer(png()).use { server ->
            ArtworkIdentities.publish(mapOf(server.url to "old"))
            val old = ArtworkIdentities.request(server.url)
            val pending = async { artworkBitmap(context, old) }
            try {
                assertTrue("first body did not start", server.firstBody.await(8, TimeUnit.SECONDS))
                ArtworkIdentities.publish(mapOf(server.url to "replacement"))
                server.release.countDown()
                assertNull(withTimeout(8_000) { pending.await() })
                assertNull(disk.read("${server.url}#128", "old"))
                val replacement = ArtworkIdentities.request(server.url)
                val bitmap = withTimeout(8_000) { artworkBitmap(context, replacement) }
                assertNotNull(bitmap)
                assertNotNull(disk.read("${server.url}#128", "replacement"))
                assertSame(bitmap, artworkBitmap(context, replacement))
                assertEquals(2, server.requests.get())
            } finally { server.release.countDown(); pending.cancelAndJoin() }
        }
    }

    @Test fun cancellingActualArtworkBodyPublishesNeitherMemoryNorDisk() = runBlocking(Dispatchers.IO) {
        forgetArtwork(disk)
        HeldArtworkServer(png()).use { server ->
            ArtworkIdentities.publish(mapOf(server.url to "current"))
            val request = ArtworkIdentities.request(server.url)
            val pending = async { artworkBitmap(context, request) }
            try {
                assertTrue("first body did not start", server.firstBody.await(8, TimeUnit.SECONDS))
                withTimeout(8_000) { pending.cancelAndJoin() }
                assertNull(disk.read("${server.url}#128", "current"))
                server.release.countDown()
                assertNotNull(withTimeout(8_000) { artworkBitmap(context, request) })
                assertEquals("cancelled load must not seed the memory cache", 2, server.requests.get())
            } finally { server.release.countDown(); pending.cancelAndJoin() }
        }
    }

    private fun png() = requireNotNull(javaClass.getResourceAsStream(
        "/fixtures/notification-art-64.png")).use { it.readBytes() }

    /** Local fixture gates a partial body; close drains its only worker on both success and failure. */
    private class HeldArtworkServer(private val bytes: ByteArray) : Closeable {
        private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        private val peer = AtomicReference<Socket?>()
        val url = "http://127.0.0.1:${listener.localPort}/art"
        val firstBody = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        private val worker = thread(isDaemon = true, name = "muon-artwork-integration") {
            try {
                repeat(2) { index ->
                    listener.accept().use { socket ->
                        peer.set(socket)
                        socket.soTimeout = 8_000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        val output = socket.getOutputStream()
                        try {
                            val header = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray()
                            output.write(header + bytes.copyOfRange(0, 1)); output.flush()
                            if (index == 0) {
                                firstBody.countDown()
                                check(release.await(10, TimeUnit.SECONDS)) { "fixture body was not released" }
                            }
                            output.write(bytes, 1, bytes.size - 1); output.flush()
                        } catch (_: IOException) { /* first request may be cancelled deliberately */ }
                    }
                }
            } catch (_: IOException) { /* listener closed after an assertion failure */ }
        }
        override fun close() {
            release.countDown()
            listener.close()
            peer.get()?.close()
            worker.join(10_000)
            check(!worker.isAlive) { "artwork fixture did not drain" }
        }
    }
}
