package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Actual pinned HTTP/1 bridge over disposable JVM loopback sockets. This is not an Android socket,
 * downloader/cache drain or card-loss fixture. Each source has one owner; cleanup closes the peer
 * and drains all owned threads before returning, including after a failed assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HttpSourceCancellationControlTest {
    private val servers = mutableListOf<Peer>()
    private val clients = mutableListOf<OkHttpClient>()
    private val readers = mutableListOf<Reader>()

    @After fun tearDown() {
        // Do not infer completion from Call.cancel or source.close alone. Release the controlled
        // peer first, then require actual owner and dispatcher termination. There is no cache here.
        servers.forEach { it.stop() }
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
        readers.forEach { it.owner.interrupt(); it.owner.join(5000) }
        clients.forEach { it.dispatcher.executorService.shutdown() }
        val dispatchersDone = clients.map { it.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS) }
        servers.forEach { it.owner.join(5000) }
        assertTrue("Every HTTP source owner terminated", readers.all { !it.owner.isAlive && it.done.count == 0L })
        assertTrue("Every source close returned", readers.all { it.closeReturned })
        assertTrue("Every dispatcher terminated", dispatchersDone.all { it })
        assertTrue("Every loopback peer terminated", servers.all { !it.owner.isAlive })
        assertTrue("No unexpected peer failure", servers.all { it.failure.get() == null })
    }

    @Test fun interruptWhileWaitingForHeadersCancelsTheActualCallAndClosesTheSource() {
        val peer = Peer(sendPartialBody = false).also { servers += it }
        val canceled = AtomicReference<Call?>()
        val client = client(readTimeoutMillis = 10000, listener = object : EventListener() {
            override fun canceled(call: Call) { canceled.set(call) }
        })
        val source = OkHttpDataSource.Factory(client).createDataSource()
        val reader = Reader(source) { source.open(DataSpec(Uri.parse(peer.url))) }.also { readers += it }
        assertTrue("The server received the real HTTP request and withholds all headers",
            peer.requestReceived.await(5, TimeUnit.SECONDS))
        assertEquals("No response headers were sent", 1L, peer.responseSent.count)

        reader.owner.interrupt()

        assertTrue("The interrupted header wait and source close returned", reader.done.await(5, TimeUnit.SECONDS))
        reader.owner.join(5000)
        val error = requireNotNull(reader.failure.get()) { "The interrupted open must fail" }
        assertTrue(error is HttpDataSourceException)
        assertEquals(HttpDataSourceException.TYPE_OPEN, (error as HttpDataSourceException).type)
        assertTrue("Media3 reports interruption", causes(error).any { it is InterruptedIOException })
        assertTrue("Media3 executeCall canceled its real Call", canceled.get()?.isCanceled() == true)
        assertTrue(reader.closeReturned)
        assertFalse(reader.owner.isAlive)
        assertEquals("The peer still supplied no response; cancellation did not depend on EOF",
            1L, peer.responseSent.count)
    }

    @Test fun aPartialBodyReadTimeoutEndsTheActualSourceOwnerBeforeCleanup() {
        val peer = Peer(sendPartialBody = true).also { servers += it }
        val client = client(readTimeoutMillis = 1000)
        val source = OkHttpDataSource.Factory(client).createDataSource()
        val firstRead = CountDownLatch(1)
        val secondRead = CountDownLatch(1)
        val reader = Reader(source) {
            assertEquals(2L, source.open(DataSpec(Uri.parse(peer.url))))
            val buffer = ByteArray(1)
            assertEquals(1, source.read(buffer, 0, 1))
            assertEquals(42, buffer[0].toInt())
            firstRead.countDown()
            secondRead.countDown()
            source.read(buffer, 0, 1) // The peer keeps the remaining byte and socket open.
            fail("The incomplete body must not be treated as success")
        }.also { readers += it }
        assertTrue("Actual first body byte reached Media3", firstRead.await(5, TimeUnit.SECONDS))
        assertTrue("The next read was requested", secondRead.await(5, TimeUnit.SECONDS))
        assertTrue("Configured test timeout ended the source owner", reader.done.await(5, TimeUnit.SECONDS))
        reader.owner.join(5000)
        val error = requireNotNull(reader.failure.get()) { "A silent incomplete body must fail" }
        assertTrue(error is HttpDataSourceException)
        assertEquals(HttpDataSourceException.TYPE_READ, (error as HttpDataSourceException).type)
        assertTrue("Native JVM HTTP/1 socket timeout reached the bridge",
            causes(error).any { it is SocketTimeoutException })
        assertEquals("The peer still withholds its last byte; cleanup/EOF did not end the read",
            1L, peer.release.count)
        assertTrue(reader.closeReturned)
        assertFalse(reader.owner.isAlive)
    }

    private fun client(readTimeoutMillis: Long, listener: EventListener = EventListener.NONE): OkHttpClient =
        OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).eventListener(listener).build().also { clients += it }

    private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }

    private class Reader(source: OkHttpDataSource, work: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        @Volatile var closeReturned = false
        val owner = thread(name = "muon-http-source-control", isDaemon = true) {
            try { work() } catch (error: Throwable) { failure.set(error) }
            finally {
                try { source.close(); closeReturned = true }
                catch (error: Throwable) {
                    val existing = failure.get()
                    if (existing == null) failure.set(error) else existing.addSuppressed(error)
                } finally { done.countDown() }
            }
        }
    }

    private class Peer(private val sendPartialBody: Boolean) {
        // Bind explicitly to loopback, never a wildcard/LAN address or the user's Tauon server.
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/disposable-payload"
        val requestReceived = CountDownLatch(1)
        val responseSent = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        @Volatile private var socket: Socket? = null
        @Volatile private var stopping = false
        val owner = thread(name = "muon-http-peer-control", isDaemon = true) {
            try {
                listener.soTimeout = 5000
                listener.accept().use { peer ->
                    socket = peer
                    peer.soTimeout = 5000
                    val input = peer.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    var complete = false
                    repeat(64) {
                        if (!complete) {
                            val line = input.readLine() ?: error("Request ended before its headers")
                            if (line.isEmpty()) complete = true
                        }
                    }
                    check(complete) { "Unexpected request header count" }
                    requestReceived.countDown()
                    if (sendPartialBody) {
                        val output = peer.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        output.write(42)
                        output.flush()
                        responseSent.countDown()
                    }
                    check(release.await(15, TimeUnit.SECONDS)) { "Peer cleanup was not requested" }
                    // Never send the last byte or response after release; close is cleanup only.
                }
            } catch (error: Throwable) {
                if (!stopping) failure.set(error)
            }
        }

        fun stop() {
            stopping = true
            release.countDown()
            runCatching { socket?.close() }
            runCatching { listener.close() }
        }
    }
}
