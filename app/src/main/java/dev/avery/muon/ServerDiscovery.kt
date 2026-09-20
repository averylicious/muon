package dev.avery.muon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Optional, bounded Tauon DNS-SD discovery. Manual numeric LAN entry remains available. */
class ServerDiscovery(
    context: Context,
    private val found: (String, String) -> Unit,
    private val message: (String) -> Unit,
    private val stateChanged: (DiscoverySnapshot) -> Unit = {},
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scan = DiscoveryScan<NsdServiceInfo>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var timeout: Runnable? = null
    // API 28's resolveService cannot be cancelled. Keep its slot across scan restarts;
    // its completion can drain the next scan, but cannot insert an old result into it.
    private var resolving: DiscoveryRequest<NsdServiceInfo>? = null

    fun start() {
        stop()
        val token = scan.begin()
        publish()
        message("Looking for Tauon on your LAN…")
        val callback = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                if (info.serviceType.trimEnd('.') != "_tauon-remote._tcp") return@post
                scan.found(token, key(info), info)
                drain()
            } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post {
                if (!scan.accepts(token)) return@post
                scan.lost(token, key(info)); publish()
            } }
            override fun onDiscoveryStopped(type: String) { handler.post {
                if (scan.accepts(token)) finish(token)
            } }
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post {
                if (scan.accepts(token)) finish(token, unavailable = true)
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
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(request.value, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post {
                    resolved(request, null)
                } }
                override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                    val host = service.host?.hostAddress
                    val server = host?.let {
                        val literal = if (':' in it) "[$it]" else it
                        runCatching { ServerEndpoint.parse("http://$literal:${service.port}") }
                            .getOrNull()?.let { endpoint -> DiscoveredServer(service.serviceName, endpoint.origin) }
                    }
                    resolved(request, server)
                } }
            })
        } catch (_: RuntimeException) {
            // Post instead of recursing through a whole queue if the platform refuses resolves.
            handler.post { resolved(request, null) }
        }
    }

    private fun resolved(request: DiscoveryRequest<NsdServiceInfo>, server: DiscoveredServer?) {
        if (resolving != request) return
        resolving = null
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
    }
    private fun publish() = stateChanged(scan.snapshot())
    private fun key(info: NsdServiceInfo) = "${info.serviceType.trimEnd('.')}/${info.serviceName}"
}
