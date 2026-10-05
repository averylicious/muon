package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * #179: when does a real downloader invocation actually finish? The actual pinned ProgressiveDownloader
 * -> CacheWriter -> CacheDataSource (+ CacheDataSink, SimpleCache, native SQLite) -> OkHttpDataSource,
 * over a disposable HTTP/1 peer bound to 127.0.0.1 only. The upstream and sink are wrapped only to
 * record calls; they delegate every operation, so socket behaviour is the real JVM's. The caller is a
 * test-owned thread standing in for DownloadManager's task thread; no manager, generation or card is
 * involved. JVM sockets, not Android's: no device timing is shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class HttpDownloaderBoundaryTest {
    @get:Rule val folders = TemporaryFolder()
    private val key = "disposable-http-download"
    private val events = CopyOnWriteArrayList<String>()
    private var database: StandaloneDatabaseProvider? = null
    private var cache: SimpleCache? = null
    // Daemon, so a failed drain cannot keep the test JVM alive; never shut down before its callers return.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "muon-download-runnable").apply { isDaemon = true } }
    private val peers = mutableListOf<Peer>()
    private val clients = mutableListOf<OkHttpClient>()
    private val downloaders = mutableListOf<ProgressiveDownloader>()
    private val callers = mutableListOf<Caller>()
    private val sources = mutableListOf<RecordingSource>()
    private val sinks = mutableListOf<RecordingSink>()

    @After fun tearDown() {
        // The controlled peer goes first, so any read it is holding ends; then cancel and join every caller.
        peers.forEach { it.stop() }
        downloaders.forEach { runCatching { it.cancel() } }
        callers.forEach { it.thread.join(5000) }
        val callersDone = callers.all { !it.thread.isAlive }
        // The executor must stay usable until every admitted download() has returned: a refused submission
        // would leave download() waiting forever in blockUntilFinished.
        if (callersDone) executor.shutdown()
        val executorDone = callersDone && executor.awaitTermination(5, TimeUnit.SECONDS)
        clients.forEach {
            it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown()
        }
        val dispatchersDone = clients.map { it.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS) }
        peers.forEach { it.owner.join(5000) }
        val closed = sources.all { it.opens.get() == 0 || it.closeReturned.get() } &&
            sinks.all { it.opens.get() == 0 || it.closeReturned.get() }
        // Release the disposable cache only after the invocation, its worker and its source and sink are done.
        if (callersDone && executorDone && closed) {
            try { cache?.release() } finally { database?.close() }
        }
        assertTrue("Every download() caller returned", callersDone)
        assertTrue("The download executor terminated", executorDone)
        assertTrue("Every opened source and sink closed", closed)
        assertTrue("Every OkHttp dispatcher terminated", dispatchersDone.all { it })
        assertTrue("Every loopback peer terminated", peers.all { !it.owner.isAlive })
        assertTrue("No unexpected peer failure", peers.all { it.failure.get() == null })
    }

    @Test fun cancelDuringTheHeaderWaitCancelsTheRealCallAndClosesTheSourceBeforeDownloadReturns() {
        newCache() // Native setup first, so it does not use up the peer's accept window.
        val peer = Peer(sendPartialBody = false).also { peers += it }
        val canceled = AtomicReference<Call?>()
        val client = client(readTimeoutMillis = 10_000, listener = object : EventListener() {
            override fun canceled(call: Call) { canceled.set(call) }
        })
        val (downloader, source, sink) = downloader(peer, client)
        val caller = Caller(downloader).also { callers += it }
        assertTrue("The peer received the real request and withholds every header",
            peer.requestReceived.await(5, TimeUnit.SECONDS))

        downloader.cancel() // ProgressiveDownloader: cancel the writer and interrupt its worker.

        assertTrue("download() returned", caller.done.await(5, TimeUnit.SECONDS))
        caller.thread.join(5000)
        assertTrue("A canceled download reports cancellation", caller.failure.get() is CancellationException)
        assertTrue("Media3's header wait canceled the real Call", canceled.get()?.isCanceled() == true)
        val open = requireNotNull(source.openFailure.get()) { "The interrupted open must fail" }
        assertTrue(open is HttpDataSourceException)
        assertEquals(HttpDataSourceException.TYPE_OPEN, (open as HttpDataSourceException).type)
        assertTrue(causes(open).any { it is InterruptedIOException && it !is SocketTimeoutException })
        assertTrue("The real source closed", source.closeReturned.get())
        assertEquals("No body was opened, so no sink", 0, sink.opens.get())
        assertBefore("source.close", "download.returned")
        executor.shutdown()
        assertTrue("The worker that ran the writer finished", executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("The peer never sent a response; nothing ended the wait but cancellation",
            1L, peer.responseSent.count)
        assertTrue(requireNotNull(cache).getCachedSpans(key).isEmpty())
    }

    @Test fun aHeldPartialBodyTimesOutAndClosesSourceAndSinkBeforeDownloadReturns() {
        newCache()
        val peer = Peer(sendPartialBody = true).also { peers += it }
        val client = client(readTimeoutMillis = 1000)
        val (downloader, source, sink) = downloader(peer, client)
        val caller = Caller(downloader).also { callers += it }
        assertTrue("The peer sent headers and one of two body bytes", peer.responseSent.await(5, TimeUnit.SECONDS))

        // Nothing cancels this one: only the configured JVM read timeout can end the held read.
        assertTrue("download() returned", caller.done.await(10, TimeUnit.SECONDS))
        caller.thread.join(5000)
        val failure = requireNotNull(caller.failure.get()) { "An incomplete body must not complete the download" }
        assertTrue(failure is HttpDataSourceException)
        assertEquals(HttpDataSourceException.TYPE_READ, (failure as HttpDataSourceException).type)
        assertTrue(causes(failure).any { it is SocketTimeoutException })
        assertTrue(source.closeReturned.get())
        assertTrue(sink.closeReturned.get())
        assertBefore("source.close", "download.returned")
        assertBefore("sink.close", "download.returned")
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("The peer still holds its last byte: neither cleanup nor EOF ended the read",
            1L, peer.release.count)
        val cache = requireNotNull(cache)
        assertEquals("Only the received byte was committed", 1L, cache.getCachedBytes(key, 0, 2))
        assertFalse("One byte is not the complete two-byte resource", cache.isCached(key, 0, 2))
        val span = cache.getCachedSpans(key).single()
        assertEquals(0L, span.position)
        assertArrayEquals(byteArrayOf(42), requireNotNull(span.file).readBytes())
    }

    @Test fun cancelAndInterruptAfterTheFirstByteStillReturnOnlyAfterSourceAndSinkClose() {
        newCache()
        val peer = Peer(sendPartialBody = true).also { peers += it }
        // Long enough that the cancel lands well before it; short enough to bound the case if the read
        // turns out to be blocked where an interrupt cannot reach it.
        val client = client(readTimeoutMillis = 3000)
        val (downloader, source, sink) = downloader(peer, client)
        val caller = Caller(downloader).also { callers += it }
        assertTrue("The first body byte reached the source", source.firstByte.await(5, TimeUnit.SECONDS))
        // Entered the wrapper's next read: this does NOT show that the socket read itself is blocked yet.
        assertTrue("The next read was requested", source.nextRead.await(5, TimeUnit.SECONDS))

        // What DownloadManager's task cancel does: cancel the downloader, then interrupt its caller.
        downloader.cancel()
        caller.thread.interrupt()

        assertTrue("download() returned", caller.done.await(10, TimeUnit.SECONDS))
        caller.thread.join(5000)
        val failure = caller.failure.get()
        assertTrue("The caller saw cancellation or its own interrupt",
            failure is CancellationException || failure is InterruptedException)
        val read = requireNotNull(source.readFailure.get()) { "The held read must end in a failure" }
        val interrupted = causes(read).any { it is InterruptedIOException && it !is SocketTimeoutException }
        val timedOut = causes(read).any { it is SocketTimeoutException }
        assertTrue("The read ended by interruption or by the configured timeout, not by the peer", interrupted || timedOut)
        println("MUON_HTTP_DOWNLOADER_BOUNDARY cancel-after-first-byte read ended by " +
            if (interrupted) "interruption" else "the configured read timeout")
        assertTrue(source.closeReturned.get())
        assertTrue(sink.closeReturned.get())
        assertBefore("source.close", "download.returned")
        assertBefore("sink.close", "download.returned")
        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals("The peer still holds its last byte", 1L, peer.release.count)
        val cache = requireNotNull(cache)
        assertFalse(cache.isCached(key, 0, 2))
        assertTrue(cache.getCachedBytes(key, 0, 2) <= 1L)
    }

    private fun newCache() {
        val provider = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()).also { database = it }
        cache = SimpleCache(folders.newFolder("download-cache"), NoOpCacheEvictor(), provider)
            .also { it.checkInitialization() }
    }

    private fun client(readTimeoutMillis: Long, listener: EventListener = EventListener.NONE): OkHttpClient =
        OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false).eventListener(listener).build().also { clients += it }

    private fun downloader(peer: Peer, client: OkHttpClient): Triple<ProgressiveDownloader, RecordingSource, RecordingSink> {
        val cache = requireNotNull(cache)
        val source = RecordingSource(OkHttpDataSource.Factory(client).createDataSource()).also { sources += it }
        val sink = RecordingSink(CacheDataSink.Factory().setCache(cache).createDataSink()).also { sinks += it }
        val factory = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory { source }.setCacheWriteDataSinkFactory { sink }
        val item = MediaItem.Builder().setUri(peer.url).setCustomCacheKey(key).build()
        val downloader = ProgressiveDownloader(item, factory, executor).also { downloaders += it }
        return Triple(downloader, source, sink)
    }

    private fun assertBefore(first: String, then: String) {
        val a = events.indexOf(first)
        val b = events.indexOf(then)
        assertTrue("$first was recorded", a >= 0)
        assertTrue("$then was recorded", b >= 0)
        assertTrue("$first happened before $then: $events", a < b)
    }

    private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }

    /** The test's stand-in for DownloadManager's task thread: one download() call. */
    private inner class Caller(downloader: ProgressiveDownloader) {
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val thread = thread(name = "muon-download-caller", isDaemon = true) {
            try { downloader.download(null) } catch (error: Throwable) { failure.set(error) }
            finally { events += "download.returned"; done.countDown() }
        }
    }

    /** Delegates every call to the real OkHttpDataSource; only records what happened. */
    private inner class RecordingSource(private val delegate: DataSource) : DataSource {
        val opens = AtomicInteger()
        val bytes = AtomicLong()
        val firstByte = CountDownLatch(1)
        val nextRead = CountDownLatch(1)
        val openFailure = AtomicReference<Throwable?>()
        val readFailure = AtomicReference<Throwable?>()
        val closeReturned = AtomicBoolean()

        override fun addTransferListener(transferListener: TransferListener) = delegate.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            opens.incrementAndGet()
            try { return delegate.open(dataSpec) } catch (error: Throwable) { openFailure.compareAndSet(null, error); throw error }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytes.get() > 0) nextRead.countDown()
            try {
                val count = delegate.read(buffer, offset, length)
                if (count > 0 && bytes.addAndGet(count.toLong()) > 0) firstByte.countDown()
                return count
            } catch (error: Throwable) { readFailure.compareAndSet(null, error); throw error }
        }

        override fun getUri() = delegate.uri

        override fun getResponseHeaders() = delegate.responseHeaders

        override fun close() {
            delegate.close()
            closeReturned.set(true)
            events += "source.close"
        }
    }

    /** Delegates every call to the real CacheDataSink; only records that its close returned. */
    private inner class RecordingSink(private val actual: DataSink) : DataSink {
        val opens = AtomicInteger()
        val closeReturned = AtomicBoolean()

        override fun open(dataSpec: DataSpec) { opens.incrementAndGet(); actual.open(dataSpec) }

        override fun write(buffer: ByteArray, offset: Int, length: Int) = actual.write(buffer, offset, length)

        override fun close() {
            actual.close()
            closeReturned.set(true)
            events += "sink.close"
        }
    }

    /** One connection on 127.0.0.1, bounded request parsing; holds whatever it was told to send until stopped. */
    private class Peer(private val sendPartialBody: Boolean) {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}/disposable-payload"
        val requestReceived = CountDownLatch(1)
        val responseSent = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        @Volatile private var socket: Socket? = null
        @Volatile private var stopping = false
        val owner = thread(name = "muon-http-download-peer", isDaemon = true) {
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
                    // Never sends the last byte or a response after release; closing is cleanup only.
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
