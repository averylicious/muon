package dev.avery.muon

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Actual model/API/Call cancellation and retry; no Compose, user data, native cache or phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LibraryConnectionCancellationTest {
    private lateinit var app: Application
    private lateinit var model: LibraryModel
    private var previousOffline = false

    @Before fun prepare() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("connection", 0).edit().clear().commit()
        previousOffline = OfflineStore.offline
        model = LibraryModel(app)
    }
    @After fun finish() {
        val job = model.viewModelScope.coroutineContext[Job]
        model.viewModelScope.cancel()
        pump { job?.isCompleted != false }
        OfflineStore.offline = previousOffline
    }

    @Test fun cancelClosesTheInitialRequestWithoutPersistingItsAddressAndPermitsRetry() {
        Api(holdVersion = 1).use { api ->
            model.address = api.origin
            model.connect()
            pump { api.held.count == 0L }
            assertTrue(model.busy)
            val pending = model.viewModelScope.coroutineContext[Job]!!.children.single()
            model.cancelLoad()
            assertFalse(model.busy)
            assertNull(model.error)
            assertNull(model.endpoint)
            assertFalse(app.getSharedPreferences("connection", 0).contains("origin"))
            assertTrue("Cancelling the model must close its active body", api.cancelled.await(5, TimeUnit.SECONDS))
            pump { pending.isCompleted }
            model.connect()
            pump { !model.busy }
            assertNull(model.error)
            assertEquals(api.origin, model.endpoint?.origin)
            assertEquals("Fixture", model.playlists.single().name)
            assertEquals(api.origin, app.getSharedPreferences("connection", 0).getString("origin", null))
            assertEquals("The cancelled version must not proceed to playlists", 1, api.playlists.get())
        }
    }

    @Test fun cancellingARefreshKeepsThePublishedLibraryAndDoesNotApplyALateFailure() {
        Api(holdVersion = 2).use { api ->
            model.address = api.origin
            model.connect(); pump { !model.busy }
            assertNull(model.error)
            val endpoint = model.endpoint
            val playlists = model.playlists
            val tracks = model.tracksByPlaylist
            model.connect(); pump { api.held.count == 0L }
            val pending = model.viewModelScope.coroutineContext[Job]!!.children.single()
            model.cancelLoad()
            assertFalse(model.busy)
            assertSame(endpoint, model.endpoint)
            assertSame(playlists, model.playlists)
            assertSame(tracks, model.tracksByPlaylist)
            assertTrue(api.cancelled.await(5, TimeUnit.SECONDS))
            // Await actual IO/main completion, not just the Cancel button's immediate busy change.
            pump { pending.isCompleted }
            assertNull(model.error)
            assertFalse(model.offline)
            assertEquals(api.origin, app.getSharedPreferences("connection", 0).getString("origin", null))
            model.cancelLoad() // A late/double Cancel must not disconnect a completed library.
            assertSame(endpoint, model.endpoint)
        }
    }

    private fun pump(done: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(1)
        } while (System.nanoTime() < end)
        fail("The model/API fixture did not settle")
    }

    /** Serial local API; one chosen version body waits for the client's actual socket close. */
    private class Api(private val holdVersion: Int) : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val active = AtomicReference<Socket?>()
        private val closing = AtomicBoolean()
        private val versions = AtomicInteger()
        val playlists = AtomicInteger()
        val held = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val origin = "http://127.0.0.1:${server.localPort}"
        private val worker = thread(isDaemon = true, name = "library-cancellation-api") {
            try {
                while (!closing.get()) server.accept().use { peer ->
                    active.set(peer); peer.soTimeout = 10_000
                    val reader = peer.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val output = peer.getOutputStream()
                    if (path == "/api1/version" && versions.incrementAndGet() == holdVersion) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\nConnection: close\r\n\r\n{".toByteArray())
                        output.flush(); held.countDown()
                        try { if (reader.read() == -1) cancelled.countDown() }
                        catch (timeout: java.net.SocketTimeoutException) { throw timeout }
                        catch (_: IOException) { cancelled.countDown() } // FIN and reset both close the client.
                    } else {
                        val body = when (path) {
                            "/api1/version" -> "{\"version\":1}"
                            "/api1/playlists" -> {
                                playlists.incrementAndGet()
                                "{\"playlists\":[{\"id\":\"1\",\"name\":\"Fixture\",\"count\":0}]}"
                            }
                            "/api1/tracklist/1" -> "{\"tracks\":[]}"
                            else -> throw IOException("Unexpected fixture path")
                        }.toByteArray()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(body); output.flush()
                    }
                    active.set(null)
                }
            } catch (_: IOException) { /* Closing a stalled or listening socket is fixture teardown. */ }
        }
        override fun close() {
            closing.set(true); active.getAndSet(null)?.close(); server.close(); worker.join(12_000)
            check(!worker.isAlive) { "Loopback fixture did not drain" }
        }
    }
}
