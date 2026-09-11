package dev.avery.muon

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/** Optional Tauon DNS-SD discovery. Manual numeric LAN entry always remains available. */
class ServerDiscovery(context: Context, private val found: (String, String) -> Unit,
    private val message: (String) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var resolving = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { handler.post { message("Looking for Tauon on your LAN…") } }
        override fun onServiceFound(info: NsdServiceInfo) {
            handler.post {
                if (!active || resolving) return@post
                resolving = true
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) { handler.post { resolving = false } }
                    override fun onServiceResolved(service: NsdServiceInfo) {
                        handler.post {
                            resolving = false
                            if (!active) return@post
                            val host = service.host?.hostAddress ?: return@post
                            val literal = if (':' in host) "[$host]" else host
                            runCatching { ServerEndpoint.parse("http://$literal:${service.port}") }.onSuccess {
                                found(service.serviceName, it.origin)
                            }
                        }
                    }
                })
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { stop(); message("Discovery unavailable. Enter the LAN address manually.") } }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
    }
    private val finish = Runnable { stop(); message("Discovery finished. If Tauon is missing, enter its LAN address.") }
    fun start() {
        stop(); active = true
        runCatching { manager.discoverServices("_tauon-remote._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { active = false; message("Discovery unavailable. Enter the LAN address manually.") }
        handler.postDelayed(finish, 10000)
    }
    fun stop() {
        handler.removeCallbacks(finish)
        if (active) { active = false; runCatching { manager.stopServiceDiscovery(listener) } }
    }
}
