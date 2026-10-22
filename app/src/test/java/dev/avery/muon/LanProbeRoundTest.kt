package dev.avery.muon

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Network snapshots/callbacks are simulated. Every real socket below is loopback only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34], manifest = Config.NONE)
class LanProbeRoundTest {
    @Suppress("DEPRECATION")
    @Test fun observedDefaultKeepsItsFactoryAndLossIsTerminalUntilANewRound() {
        fun caps(vararg transports: Int) = ShadowNetworkCapabilities.newInstance().apply {
            transports.forEach { shadowOf(this).addTransportType(it) }
        }
        val wifi = caps(NetworkCapabilities.TRANSPORT_WIFI)
        val vpnWifi = caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI)
        val mixed = caps(NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR)
        assertTrue(probeNetworkEligible(wifi))
        assertTrue(probeNetworkEligible(vpnWifi))
        assertTrue(probeNetworkEligible(caps(NetworkCapabilities.TRANSPORT_ETHERNET)))
        assertFalse(probeNetworkEligible(mixed))
        assertFalse(probeNetworkEligible(caps(NetworkCapabilities.TRANSPORT_VPN)))
        assertFalse(probeNetworkEligible(null))

        val context = RuntimeEnvironment.getApplication<Application>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val selected = connectivity.activeNetwork!!
        shadow.setNetworkCapabilities(selected, vpnWifi)
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName("192.168.1.42")),
            ClassParameter.from(Int::class.javaPrimitiveType!!, 24))
        shadow.setLinkProperties(selected, LinkProperties().apply {
            ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress",
                ClassParameter.from(LinkAddress::class.java, address))
        })
        val factory = CountingSocketFactory()
        shadowOf(selected).setSocketFactory(factory)

        LanProbe.open(context)!!.use { round ->
            assertTrue(round.isCurrent())
            assertSame(factory, round.client.socketFactory)
            assertEquals(1, shadow.networkCallbacks.size)
            // A VPN can keep the same identity while its reported underlying transports change.
            shadow.networkCallbacks.single().onCapabilitiesChanged(selected, mixed)
            assertFalse(round.isCurrent())
            shadow.setNetworkCapabilities(selected, wifi)
            assertFalse("An old round must not revive", round.isCurrent())
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
        LanProbe.open(context)!!.use { replacement ->
            assertTrue(replacement.isCurrent())
            shadow.networkCallbacks.single().onAvailable(ShadowNetwork.newInstance(1234))
            assertFalse(replacement.isCurrent())
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
        LanProbe.open(context)!!.use { replacement ->
            // Fresh eligibility checks also catch a changed default before its callback arrives.
            shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_MOBILE, 0, true, true))
            assertFalse(replacement.isCurrent())
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
        assertEquals(0, factory.created.get()) // The observer test never opens any socket.
    }

    @Suppress("DEPRECATION")
    @Test fun ipv6OnlyWifiOwnsAnEmptyProbeWhileNsdCanCompleteAndLossStillClearsIt() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication<Application>()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        val selected = connectivity.activeNetwork!!
        val wifi = ShadowNetworkCapabilities.newInstance().apply {
            shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        shadow.setNetworkCapabilities(selected, wifi)
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
            ClassParameter.from(InetAddress::class.java, InetAddress.getByName("fd00::42")),
            ClassParameter.from(Int::class.javaPrimitiveType!!, 64))
        val properties = LinkProperties().apply {
            ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress",
                ClassParameter.from(LinkAddress::class.java, address))
        }
        shadow.setLinkProperties(selected, properties)
        val factory = CountingSocketFactory()
        shadowOf(selected).setSocketFactory(factory)
        val server = DiscoveredServer("IPv6 fixture", ServerEndpoint.parse("http://[fd00::7]").origin)
        val nsd = DiscoverySnapshot(DiscoveryStatus.COMPLETE, listOf(server))

        LanProbe.open(context)!!.use { round ->
            assertTrue(round.isCurrent())
            assertSame(factory, round.client.socketFactory)
            assertEquals(1, shadow.networkCallbacks.size)
            shadow.networkCallbacks.single().onLinkPropertiesChanged(selected, properties)
            assertTrue(round.isCurrent())
            val probe = round.find()
            assertTrue(probe.isEmpty())
            assertEquals(0, factory.created.get())
            assertEquals(server, autoConnectTarget(
                combineDiscovery(nsd, probe, networkCurrent = round.isCurrent()), false, false))

            shadow.networkCallbacks.single().onLost(selected)
            val lost = combineDiscovery(nsd, probe, networkCurrent = round.isCurrent())
            assertEquals(DiscoverySnapshot(DiscoveryStatus.UNAVAILABLE), lost)
            assertNull(autoConnectTarget(lost, false, false))
        }
        assertTrue(shadow.networkCallbacks.isEmpty())
    }

    @Test fun roundCannotReuseABasePoolConnectionOrCleanUpTheSharedDispatcher() = runBlocking(Dispatchers.IO) {
        ProbeServer().use { server ->
            val base = Transport.client.newBuilder().connectionPool(ConnectionPool()).build()
            try {
                assertEquals(VERSION, body(base, server.origin))
                assertEquals(1, base.connectionPool.idleConnectionCount())
                val factory = CountingSocketFactory()
                LanProbeRound(factory, { true }, base = base).use { round ->
                    assertNotSame(base.connectionPool, round.client.connectionPool)
                    assertSame(base.dispatcher, round.client.dispatcher)
                    assertEquals(DiscoveredServer("Tauon", server.origin), round.answer(server.origin))
                    assertEquals(1, factory.created.get())
                    assertEquals(0, round.client.connectionPool.idleConnectionCount())
                    assertEquals(1, base.connectionPool.idleConnectionCount())
                }
                assertEquals(VERSION, body(base, server.origin))
                assertEquals(2, server.connections.get()) // Base connection survived round cleanup.
            } finally { base.connectionPool.evictAll() } // Only this fixture's private base pool.
        }
    }

    @Test fun invalidationCancelsAStalledCallRefusesQueuedHostsAndLeavesOtherCallsAlone() = runBlocking(Dispatchers.IO) {
        ProbeServer(stall = true).use { stalled ->
            val release = CountDownLatch(1)
            ProbeServer(reply = release).use { unrelated ->
                val base = Transport.client.newBuilder().connectionPool(ConnectionPool()).build()
                val eligible = AtomicBoolean(true)
                val factory = CountingSocketFactory()
                try {
                    LanProbeRound(factory, { eligible.get() }, base = base).use { round ->
                        val other = async { body(base, unrelated.origin) }
                        withContext(Dispatchers.IO) {
                            assertTrue(unrelated.received.await(5, TimeUnit.SECONDS))
                        }
                        val result = async { round.find(List(5) { stalled.origin }, concurrency = 1) }
                        try {
                            withContext(Dispatchers.IO) {
                                assertTrue(stalled.received.await(5, TimeUnit.SECONDS))
                            }
                            eligible.set(false)
                            round.invalidate()
                            assertTrue(withTimeout(5_000) { result.await() }.isEmpty())
                            assertTrue(stalled.closed.await(5, TimeUnit.SECONDS))
                            assertEquals(1, factory.created.get())
                            assertEquals(1, stalled.requests.get())
                            release.countDown()
                            assertEquals(VERSION, withTimeout(5_000) { other.await() })
                            eligible.set(true)
                            assertNull(round.answer(unrelated.origin)) // Completed loss is terminal.
                            var checks = 0
                            LanProbeRound(factory, { ++checks < 3 }, base = base).use { late ->
                                // The response is valid, but eligibility is lost at publication.
                                assertNull(late.answer(unrelated.origin))
                                assertFalse(late.isCurrent())
                            }
                        } finally {
                            release.countDown()
                            result.cancelAndJoin(); other.cancelAndJoin()
                        }
                    }
                } finally { base.connectionPool.evictAll() }
            }
        }
    }

    @Test fun callerCancellationEscapesClosesItsSocketAndUnregistersOnlyOnce() = runBlocking(Dispatchers.IO) {
        ProbeServer(stall = true).use { stalled ->
            val unregistered = AtomicInteger()
            val delivered = AtomicBoolean(false)
            val round = LanProbeRound(CountingSocketFactory(), { true },
                unregister = { unregistered.incrementAndGet(); Unit })
            try {
                val job = launch { round.find(listOf(stalled.origin)); delivered.set(true) }
                try {
                    withContext(Dispatchers.IO) { assertTrue(stalled.received.await(5, TimeUnit.SECONDS)) }
                    withTimeout(5_000) { job.cancelAndJoin() }
                    assertTrue(job.isCancelled)
                    assertFalse(delivered.get())
                    assertTrue(stalled.closed.await(5, TimeUnit.SECONDS))
                } finally { job.cancelAndJoin() }
            } finally { round.close(); round.close() }
            assertEquals(1, unregistered.get())
        }
    }

    private suspend fun body(client: OkHttpClient, origin: String): String =
        client.newCall(Request.Builder().url("$origin/api1/version").build())
            .readCancellable { it.body!!.string() }

    private class CountingSocketFactory : SocketFactory() {
        private val delegate = SocketFactory.getDefault()
        val created = AtomicInteger()
        override fun createSocket(): Socket = delegate.createSocket().also { created.incrementAndGet() }
        override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, local, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            delegate.createSocket(host, port, local, localPort)
    }

    private class ProbeServer(private val stall: Boolean = false, private val reply: CountDownLatch? = null) : Closeable {
        private val listener = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        private val peers = CopyOnWriteArrayList<Socket>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val stopping = AtomicBoolean(false)
        val received = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val connections = AtomicInteger()
        val requests = AtomicInteger()
        val origin = "http://127.0.0.1:${listener.localPort}"
        private val accepting = thread(isDaemon = true, name = "muon-probe-accept") {
            try {
                while (!stopping.get()) {
                    val peer = listener.accept()
                    peers.add(peer); connections.incrementAndGet()
                    workers.add(thread(isDaemon = true, name = "muon-probe-peer") {
                        try {
                            peer.use { socket ->
                                socket.soTimeout = 7_000
                                val input = socket.getInputStream().bufferedReader()
                                while (!input.readLine().isNullOrEmpty()) {
                                    while (!input.readLine().isNullOrEmpty()) { }
                                    requests.incrementAndGet(); received.countDown()
                                    reply?.await(5, TimeUnit.SECONDS)
                                    val length = if (stall) 100_000 else VERSION.length
                                    socket.getOutputStream().apply {
                                        write(("HTTP/1.1 200 OK\r\nContent-Length: $length\r\nConnection: keep-alive\r\n\r\n").toByteArray())
                                        if (!stall) write(VERSION.toByteArray())
                                        flush()
                                    }
                                    if (stall) { while (input.read() != -1) { }; break }
                                }
                            }
                        } catch (_: IOException) { /* Expected on cancellation/fixture closure. */ }
                        finally { closed.countDown() }
                    })
                }
            } catch (_: IOException) { /* Listener closed by fixture. */ }
        }

        override fun close() {
            stopping.set(true); listener.close(); peers.forEach { it.close() }
            accepting.join(7_000)
            workers.forEach { it.join(7_000); check(!it.isAlive) }
            check(!accepting.isAlive)
        }
    }

    private companion object { const val VERSION = "{\"version\":1}" }
}
