package dev.avery.muon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/** Optional, bounded Tauon DNS-SD discovery. Manual numeric LAN entry remains available. */
class ServerDiscovery(
    context: Context,
    private val found: (String, String) -> Unit,
    private val message: (String) -> Unit,
    private val stateChanged: (DiscoverySnapshot) -> Unit = {},
) {
    private val manager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scan = DiscoveryScan<NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var timeout: Runnable? = null
    // Keep the legacy SDK 28-33 logical timeout path; SDK 34+ also requests native cleanup.
    // A slot survives scan restarts until completion/timeout; old results cannot enter a new scan.
    private var resolving: DiscoveryRequest<NsdServiceInfo>? = null
    private var resolutionTimeout: Runnable? = null
    // The exact listener registered for [resolving], so API 34+ can ask the platform to stop it.
    private var resolveListener: NsdManager.ResolveListener? = null
    private var resolutionStopRequested = false

    fun start() {
        stop()
        val token = scan.begin()
        publish()
        message("Looking for Tauon on your LAN…")
        // A failed platform stop can leave its listener registered. Do not let that native
        // lifetime retain the screen's owner/callbacks; delivery still runs on main.
        val owner = WeakReference(this)
        val delivery = handler
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { delivery.post {
                val current = owner.get() ?: return@post
                if (normalizedDiscoveryType(info.serviceType) != "_tauon-remote._tcp") return@post
                current.scan.found(token, current.key(info), info)
                current.drain()
            } }
            override fun onServiceLost(info: NsdServiceInfo) { delivery.post {
                val current = owner.get() ?: return@post
                if (!current.scan.accepts(token)) return@post
                current.scan.lost(token, current.key(info)); current.publish()
            } }
            override fun onDiscoveryStopped(type: String) { delivery.post {
                val current = owner.get() ?: return@post
                if (current.scan.accepts(token)) current.finish(token)
            } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { delivery.post {
                val current = owner.get() ?: return@post
                if (current.scan.accepts(token)) current.finish(token, unavailable = true)
            } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        listener = callback
        timeout = Runnable { finish(token) }.also { handler.postDelayed(it, 10_000) }
        try {
            manager.discoverServices("_tauon-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, callback)
        } catch (_: RuntimeException) { finish(token, unavailable = true) }
    }

    private fun drain() {
        if (resolving != null) return
        val request = scan.nextRequest() ?: return
        resolving = request
        resolutionStopRequested = false
        // A platform callback may never arrive. Do not let one request lock every later scan.
        // API 34+ also asks the platform to stop it; the logical release does not wait for that.
        resolutionTimeout = Runnable {
            if (resolving == request) requestResolutionStop()
            resolved(request, null)
        }.also { handler.postDelayed(it, 5_000) }
        // Preserve #717: a native resolve may outlive a disposed screen. The listener retains
        // only a weak owner, the owner-independent Handler and this exact request.
        val owner = WeakReference(this)
        val delivery = handler
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { delivery.post {
                owner.get()?.resolved(request, null)
            } }
            override fun onServiceResolved(service: NsdServiceInfo) { delivery.post {
                val current = owner.get() ?: return@post
                val host = service.host?.hostAddress
                val server = host?.let {
                    val literal = if (':' in it) "[$it]" else it
                    runCatching { ServerEndpoint.parse("http://$literal:${service.port}") }
                        .getOrNull()?.let { endpoint -> DiscoveredServer(service.serviceName, endpoint.origin) }
                }
                current.resolved(request, server)
            } }
            // API 34+ only, after our own stopServiceResolution. AOSP unregisters the listener
            // before either callback. Still match this request against late/duplicate callbacks.
            override fun onResolutionStopped(service: NsdServiceInfo) { delivery.post {
                owner.get()?.resolutionStopped(request)
            } }
            override fun onStopResolutionFailed(service: NsdServiceInfo, code: Int) { delivery.post {
                owner.get()?.resolutionStopped(request)
            } }
        }
        resolveListener = callback
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(request.value, callback)
        } catch (_: RuntimeException) {
            // AOSP registers the listener before its binder call, which can then throw.
            // Request cleanup even here; a not-registered/stopping failure stays best effort.
            requestResolutionStop()
            // Post instead of recursing through a whole queue if the platform refuses resolves.
            handler.post { resolved(request, null) }
        }
    }

    /**
     * Best-effort native cancellation of the live resolve, submitted at most once per request.
     * Our SDK 28-33 path keeps only the logical timeout (API 33 extension support is not used). A throwing stop (for example
     * a listener the platform already dropped) also leaves the slot to that timeout.
     */
    private fun requestResolutionStop() {
        val active = resolveListener ?: return
        if (resolutionStopRequested) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolutionStopRequested = true
            try { manager.stopServiceResolution(active) } catch (_: RuntimeException) { }
        }
    }

    private fun resolutionStopped(request: DiscoveryRequest<NsdServiceInfo>) {
        // A stale request's stop callback cannot retire a newer request's slot.
        if (resolving == request && resolutionStopRequested) resolved(request, null)
    }

    private fun resolved(request: DiscoveryRequest<NsdServiceInfo>, server: DiscoveredServer?) {
        if (resolving != request) return
        resolutionTimeout?.let(handler::removeCallbacks); resolutionTimeout = null
        resolving = null; resolveListener = null; resolutionStopRequested = false
        if (scan.resolved(request, server)) {
            publish()
            if (server != null) found(server.name, server.origin)
        }
        drain()
    }
    private fun finish(token: Long, unavailable: Boolean = false) {
        if (!scan.accepts(token)) return
        scan.finish(token, unavailable)
        stopPlatform()
        publish()
        message(if (unavailable) "Discovery unavailable. Enter the LAN address manually."
            else "Discovery finished. If Tauon is missing, enter its LAN address.")
    }
    fun stop() {
        scan.cancel()
        stopPlatform()
        // Disposal must not invoke the caller's UI callbacks.
    }
    private fun stopPlatform() {
        timeout?.let(handler::removeCallbacks); timeout = null
        val old = listener; listener = null
        if (old != null) runCatching { manager.stopServiceDiscovery(old) }
        // A finished/stopped scan discards any in-flight result. Its slot and logical
        // timeout remain until the platform answers or the timeout fires.
        requestResolutionStop()
    }
    private fun publish() = stateChanged(scan.snapshot())
    private fun key(info: NsdServiceInfo) = "${normalizedDiscoveryType(info.serviceType)}/${info.serviceName}"
}
