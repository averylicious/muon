package dev.avery.muon

import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

class NetworkRequestTest {
    @Test fun finiteRequestsHaveADeadlineButAudioStreamsDoNot() {
        assertEquals(0, Transport.client.callTimeoutMillis)
        assertEquals(30_000, Transport.metadataClient.callTimeoutMillis)
        assertSame(Transport.client.connectionPool, Transport.metadataClient.connectionPool)
        assertFalse(Transport.metadataClient.followRedirects)
        assertFalse(Transport.metadataClient.followSslRedirects)
    }

    @Test fun readsACompleteBody() = runBlocking(Dispatchers.IO) {
        TestServer(Mode.COMPLETE).use { server ->
            val call = Transport.metadataClient.newCall(server.request())
            assertEquals("hello", call.readCancellable { it.body!!.string() })
            assertTrue(server.closed.await(3, TimeUnit.SECONDS))
            assertFalse(call.isCanceled())
        }
    }

    @Test fun closesTheResponseWhenTheReaderThrows() = runBlocking(Dispatchers.IO) {
        TestServer(Mode.COMPLETE).use { server ->
            val failure = IllegalArgumentException("reader failed")
            val result = runCatching {
                Transport.metadataClient.newCall(server.request()).readCancellable<Unit> { throw failure }
            }
            assertSame(failure, result.exceptionOrNull())
            assertTrue(server.closed.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun aDribblingBodyCannotResetTheOverallDeadline() = runBlocking(Dispatchers.IO) {
        TestServer(Mode.DRIBBLE).use { server ->
            val client = Transport.metadataClient.newBuilder()
                .callTimeout(600, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()
            val call = client.newCall(server.request())
            val started = System.nanoTime()
            val result = runCatching { call.readCancellable { it.body!!.bytes() } }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("expected timeout, got $result", result.exceptionOrNull() is InterruptedIOException)
            assertTrue("call was not cancelled by its deadline", call.isCanceled())
            assertTrue("dribbling response never started", server.bytesSent.get())
            assertTrue("deadline took $elapsedMs ms", elapsedMs < 5_000)
        }
    }

    @Test fun cancellationClosesASocketWaitingForHeaders() = runBlocking(Dispatchers.IO) {
        TestServer(Mode.STALL_HEADERS).use { server ->
            val call = Transport.metadataClient.newCall(server.request())
            val delivered = AtomicBoolean(false)
            val job = launch { call.readCancellable { it.body!!.bytes() }; delivered.set(true) }
            try {
                // Await on IO: do not block the runBlocking thread before launch can run.
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    assertTrue(server.received.await(3, TimeUnit.SECONDS))
                }
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertTrue(server.closed.await(3, TimeUnit.SECONDS))
                assertFalse(delivered.get())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun cancellationStaysAttachedWhileTheBodyIsBeingRead() = runBlocking(Dispatchers.IO) {
        TestServer(Mode.STALL_BODY).use { server ->
            val call = Transport.metadataClient.newCall(server.request())
            val reading = CountDownLatch(1)
            val delivered = AtomicBoolean(false)
            val job = launch {
                call.readCancellable { response -> reading.countDown(); response.body!!.bytes() }
                delivered.set(true)
            }
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    assertTrue(reading.await(3, TimeUnit.SECONDS))
                }
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertTrue(server.closed.await(3, TimeUnit.SECONDS))
                assertFalse(delivered.get())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun anAlreadyCancelledCallFailsWithoutOpeningASocket() = runBlocking(Dispatchers.IO) {
        val call = Transport.metadataClient.newCall(Request.Builder().url("http://127.0.0.1:1/").build())
        call.cancel()
        assertTrue(runCatching { call.readCancellable { it.body!!.string() } }.exceptionOrNull() is IOException)
    }

    @Test fun totalAndReadTimeoutsHaveTheSameRetryGuidance() {
        val total = friendlyError(InterruptedIOException("timeout"))
        assertEquals(friendlyError(java.net.SocketTimeoutException("read timed out")), total)
        assertTrue(total.contains("retry"))
    }

    private enum class Mode { COMPLETE, DRIBBLE, STALL_HEADERS, STALL_BODY }

    /** Loopback HTTP only; no phone, Tauon, LAN or new test dependency. */
    private class TestServer(private val mode: Mode) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val socket = AtomicReference<Socket?>()
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val bytesSent = AtomicBoolean(false)
        private val worker = thread(isDaemon = true, name = "muon-request-test") {
            try {
                listener.accept().use { peer ->
                    socket.set(peer)
                    peer.soTimeout = 6_000
                    val input = peer.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { /* consume request headers */ }
                    received.countDown()
                    val output = peer.getOutputStream()
                    if (mode != Mode.STALL_HEADERS) {
                        val length = if (mode == Mode.COMPLETE) 5 else 100_000
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: close\r\n\r\n".toByteArray())
                        if (mode == Mode.COMPLETE) output.write("hello".toByteArray())
                        output.flush()
                    }
                    if (mode == Mode.DRIBBLE) {
                        repeat(100_000) {
                            output.write('.'.code); output.flush(); bytesSent.set(true)
                            Thread.sleep(25)
                        }
                    } else {
                        // The client must close its socket on completion, reader error or cancellation.
                        while (input.read() != -1) { }
                    }
                }
            } catch (_: IOException) { /* expected when a client cancels or the fixture closes */ }
            finally { closed.countDown() }
        }

        fun request(): Request = Request.Builder().url("http://127.0.0.1:${listener.localPort}/").build()

        override fun close() {
            listener.close()
            socket.get()?.close()
            worker.join(7_000)
            check(!worker.isAlive) { "test HTTP worker did not stop" }
        }
    }
}
