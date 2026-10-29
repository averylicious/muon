package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import java.net.InetAddress
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ClassName
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

/**
 * #638 resolution-slot lifecycle through ServerDiscovery's public start/stop, its main-looper timers
 * and the platform listener callbacks. A recording NsdManager shadow stands in for the system
 * service. API 34 may ask the platform to stop a resolve; API 28 has no such call and must not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, shadows = [RecordingNsdManager::class])
class ServerDiscoveryLifecycleTest {
    private val snapshots = mutableListOf<DiscoverySnapshot>()
    private val found = mutableListOf<String>()
    private val messages = mutableListOf<String>()
    private lateinit var nsd: RecordingNsdManager
    private lateinit var discovery: ServerDiscovery

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        // The shadow's no-op constructor replaces the real one, which would bind the system service.
        val manager = Shadow.newInstance(NsdManager::class.java,
            arrayOf<Class<*>>(Context::class.java, Class.forName("android.net.nsd.INsdManager")),
            arrayOf<Any?>(app, null))
        nsd = Shadow.extract(manager)
        discovery = ServerDiscovery(NsdContext(app, manager), { name, origin -> found += "$name=$origin" },
            { messages += it }, { snapshots += it })
    }

    @Test @Config(sdk = [34])
    fun resolutionTimeoutStopsItsExactListenerOnceAndItsLateCallbacksCannotRetireANewerRequest() {
        discovery.start(); idle()
        offer("A"); offer("B"); offer("C")
        assertEquals(1, nsd.resolves.size)
        val a = listenerAt(0)

        advance(4_999)
        assertTrue(nsd.stopResolutions.isEmpty())
        advance(1)
        assertEquals(listOf(a), nsd.stopResolutions)
        assertEquals("The logical timeout does not wait for the platform", 2, nsd.resolves.size)

        a.onResolutionStopped(service("A"))
        a.onStopResolutionFailed(service("A"), NsdManager.FAILURE_INTERNAL_ERROR)
        a.onServiceResolved(answer("A", "192.168.1.20")); idle()
        assertEquals("B still owns the slot; C waits", 2, nsd.resolves.size)
        assertTrue(found.isEmpty())

        succeed(1, "192.168.1.21")
        assertEquals(listOf("B=http://192.168.1.21:7814"), found)
        assertEquals(3, nsd.resolves.size)
        assertEquals("B completed normally and is not stopped", listOf(a), nsd.stopResolutions)
    }

    @Test @Config(sdk = [34])
    fun disposalStopsTheLiveResolveOnceWithoutUiCallbacksAndStopSuccessFreesTheSlotForTheNextScan() {
        discovery.start(); idle()
        val firstScan = nsd.discoveries.single()
        offer("A")
        val a = listenerAt(0)
        val published = snapshots.size; val said = messages.size

        nsd.throwOnStopDiscovery = true
        discovery.stop(); discovery.stop(); idle()
        assertEquals(listOf(a), nsd.stopResolutions)
        assertEquals(listOf(firstScan), nsd.stoppedDiscoveries)
        assertEquals(published, snapshots.size)
        assertEquals(said, messages.size)

        discovery.start(); idle()
        offer("B")
        val beforeLate = snapshots.size; val messagesBeforeLate = messages.size
        firstScan.onServiceFound(service("stale"))
        firstScan.onServiceLost(service("B"))
        firstScan.onDiscoveryStopped("_tauon-remote._tcp.")
        firstScan.onStartDiscoveryFailed("_tauon-remote._tcp.", NsdManager.FAILURE_INTERNAL_ERROR)
        idle()
        assertEquals("An old discovery callback cannot publish to the restarted scan", beforeLate, snapshots.size)
        assertEquals(messagesBeforeLate, messages.size)
        assertEquals("Restart does not resubmit the same stop", listOf(a), nsd.stopResolutions)
        assertEquals("A keeps the slot until the platform answers", 1, nsd.resolves.size)

        a.onResolutionStopped(service("A")); idle()
        assertEquals(2, nsd.resolves.size)
        assertEquals("B", nsd.resolves[1].first.serviceName)
        assertTrue(found.isEmpty())
        assertEquals(DiscoverySnapshot(DiscoveryStatus.SEARCHING), snapshots.last())
    }

    @Test @Config(sdk = [34])
    fun stopFailureRetiresOnlyItsOwnRequest() {
        discovery.start(); idle()
        offer("A")
        val a = listenerAt(0)
        discovery.stop(); discovery.start(); idle()
        offer("B"); offer("C")
        assertEquals(1, nsd.resolves.size)

        a.onStopResolutionFailed(service("A"), NsdManager.FAILURE_INTERNAL_ERROR); idle()
        assertEquals(2, nsd.resolves.size)

        a.onStopResolutionFailed(service("A"), NsdManager.FAILURE_INTERNAL_ERROR)
        a.onResolutionStopped(service("A")); idle()
        assertEquals("Duplicate stale callbacks cannot retire B", 2, nsd.resolves.size)

        succeed(1, "192.168.1.21")
        assertEquals(listOf("B=http://192.168.1.21:7814"), found)
        assertEquals(3, nsd.resolves.size)
    }

    @Test @Config(sdk = [34])
    fun throwingStopIsContainedAndTheLogicalTimeoutStillReleasesWithoutFalseCompletion() {
        nsd.throwOnStopResolution = true
        discovery.start(); idle()
        offer("A")
        val a = listenerAt(0)
        discovery.stop()
        discovery.start(); idle()
        offer("B")
        assertEquals(listOf(a), nsd.stopResolutions)
        assertEquals(1, nsd.resolves.size)

        advance(4_999)
        assertEquals(1, nsd.resolves.size)
        advance(1)
        assertEquals(2, nsd.resolves.size)
        assertEquals("The timeout does not resubmit A", listOf(a), nsd.stopResolutions)
        assertEquals(DiscoverySnapshot(DiscoveryStatus.SEARCHING), snapshots.last())

        // At 10 s the scan finishes and B's own timeout fires; B's stop throws too.
        advance(5_000)
        assertEquals(listOf(a, listenerAt(1)), nsd.stopResolutions)
        assertEquals(DiscoverySnapshot(DiscoveryStatus.COMPLETE, emptyList(), 1), snapshots.last())
        assertEquals("Discovery finished. If Tauon is missing, enter its LAN address.", messages.last())
        assertTrue(found.isEmpty())
    }

    @Test @Config(sdk = [34])
    fun throwingResolveStillRequestsCleanupForItsPossiblyRegisteredListener() {
        nsd.throwOnResolve = true
        discovery.start(); idle()
        offer("A")
        val a = listenerAt(0)
        assertEquals(listOf(a), nsd.stopResolutions)
        assertTrue(found.isEmpty())

        nsd.throwOnResolve = false
        offer("B")
        assertEquals(2, nsd.resolves.size)
        a.onResolutionStopped(service("A")); idle()
        assertEquals("A's cleanup cannot retire B", 2, nsd.resolves.size)
        succeed(1, "192.168.1.21")
        assertEquals(listOf("B=http://192.168.1.21:7814"), found)
        assertEquals(listOf(a), nsd.stopResolutions)
    }

    @Test @Config(sdk = [34])
    fun scanTimeoutKeepsManualFallbackAndStopsAResolveWhoseResultWouldBeDiscarded() {
        discovery.start(); idle()
        advance(6_000)
        offer("A")
        val a = listenerAt(0)

        advance(4_000)
        assertEquals(DiscoverySnapshot(DiscoveryStatus.COMPLETE, emptyList(), 1), snapshots.last())
        assertEquals("Discovery finished. If Tauon is missing, enter its LAN address.", messages.last())
        assertEquals(listOf(nsd.discoveries.single()), nsd.stoppedDiscoveries)
        assertEquals(listOf(a), nsd.stopResolutions)

        val published = snapshots.size; val said = messages.size
        a.onResolutionStopped(service("A"))
        a.onServiceResolved(answer("A", "192.168.1.20")); idle()
        advance(5_000)
        assertEquals(published, snapshots.size)
        assertEquals(said, messages.size)
        assertTrue(found.isEmpty())
        assertEquals(listOf(a), nsd.stopResolutions)
    }

    @Test @Config(sdk = [28])
    fun legacyPlatformNeverRequestsNativeStopAndKeepsTheLogicalTimeoutAcrossRestart() {
        discovery.start(); idle()
        offer("A")
        val a = listenerAt(0)
        discovery.stop()
        discovery.start(); idle()
        offer("B")
        assertEquals("Uncancellable A keeps its slot across restart", 1, nsd.resolves.size)

        advance(4_999)
        assertEquals(1, nsd.resolves.size)
        advance(1)
        assertEquals(2, nsd.resolves.size)

        a.onServiceResolved(answer("A", "192.168.1.20")); idle()
        assertTrue(found.isEmpty())
        succeed(1, "192.168.1.21")
        assertEquals(listOf("B=http://192.168.1.21:7814"), found)

        advance(5_000)
        assertEquals(DiscoveryStatus.COMPLETE, snapshots.last().status)
        assertEquals(2, nsd.stoppedDiscoveries.size)
        // The shadow only declares stopServiceResolution from API 34; a call here would also fail.
        assertTrue(nsd.stopResolutions.isEmpty())
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun offer(name: String) { nsd.discoveries.last().onServiceFound(service(name)); idle() }
    private fun listenerAt(index: Int) = nsd.resolves[index].second
    private fun succeed(index: Int, host: String) {
        listenerAt(index).onServiceResolved(answer(nsd.resolves[index].first.serviceName, host)); idle()
    }
    private fun service(name: String) = NsdServiceInfo().apply {
        serviceName = name; serviceType = "_tauon-remote._tcp."
    }
    @Suppress("DEPRECATION")
    private fun answer(name: String, host: String) = service(name).apply {
        this.host = InetAddress.getByName(host); port = 7814
    }

    private class NsdContext(base: Context, private val nsd: NsdManager) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSystemServiceName(serviceClass: Class<*>): String? =
            if (serviceClass == NsdManager::class.java) Context.NSD_SERVICE else super.getSystemServiceName(serviceClass)
        override fun getSystemService(name: String): Any? =
            if (name == Context.NSD_SERVICE) nsd else super.getSystemService(name)
    }
}

/** Records platform NSD calls; no system service, binder or network is involved. */
@Implements(NsdManager::class)
class RecordingNsdManager {
    val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    val stoppedDiscoveries = mutableListOf<NsdManager.DiscoveryListener>()
    val resolves = mutableListOf<Pair<NsdServiceInfo, NsdManager.ResolveListener>>()
    val stopResolutions = mutableListOf<NsdManager.ResolveListener>()
    var throwOnStopDiscovery = false
    var throwOnStopResolution = false
    var throwOnResolve = false

    @Suppress("unused", "FunctionName", "UNUSED_PARAMETER")
    @Implementation
    fun __constructor__(context: Context, @ClassName("android.net.nsd.INsdManager") service: Any?) = Unit

    @Implementation
    fun discoverServices(serviceType: String, protocolType: Int, listener: NsdManager.DiscoveryListener) {
        discoveries += listener
    }

    @Implementation
    fun stopServiceDiscovery(listener: NsdManager.DiscoveryListener) {
        stoppedDiscoveries += listener
        if (throwOnStopDiscovery) throw IllegalStateException("stop discovery failed after registration")
    }

    @Implementation
    fun resolveService(serviceInfo: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        resolves += serviceInfo to listener
        if (throwOnResolve) throw IllegalStateException("resolve failed after registration")
    }

    // AOSP may throw for an unknown listener on older module builds; the caller must tolerate it.
    @Implementation(minSdk = 34)
    fun stopServiceResolution(listener: NsdManager.ResolveListener) {
        stopResolutions += listener
        if (throwOnStopResolution) throw IllegalArgumentException("listener not registered")
    }
}
