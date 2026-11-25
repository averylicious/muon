package dev.avery.muon

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * The addresses worth asking on this phone's own network: the other hosts of the /24 around its
 * private IPv4 address, or of its smaller subnet. A public address offers nothing to try, since
 * Muon only talks to trusted-LAN servers.
 */
internal fun probeCandidates(own: String, prefix: Int): List<String> {
    val parts = own.split('.').map { it.toIntOrNull() ?: return emptyList() }
    if (parts.size != 4 || prefix !in 0..32 ||
        runCatching { ServerEndpoint.parse(own) }.isFailure) return emptyList()
    val hosts = when {
        prefix == 32 -> return emptyList()
        // RFC 3021: both addresses on a /31 point-to-point link are hosts.
        prefix == 31 -> {
            val start = parts[3] / 2 * 2
            start..(start + 1)
        }
        prefix > 24 -> {
            val size = 1 shl (32 - prefix)
            val start = parts[3] / size * size
            (start + 1) until (start + size - 1)
        }
        else -> 1..254
    }
    return hosts.filter { it != parts[3] }.map { "${parts[0]}.${parts[1]}.${parts[2]}.$it" }
}

/**
 * Finds Tauon by asking every host on the local network for its remote API (#39). Tauon advertises
 * itself over mDNS only when its optional zeroconf package is installed and the desktop's firewall
 * lets the announcement out, so discovery alone often finds nothing; a direct question on port 7814
 * works wherever typing the address would. Automatic probes require a current Wi-Fi/Ethernet
 * default with no reported cellular transport. Capabilities/callbacks are asynchronous: a VPN can
 * change its underlying route before reporting it, so this is not a zero-cellular-packet guarantee.
 */
internal object LanProbe {
    /** Observe the selected default, including a VPN; never select an underlying network. */
    fun open(context: Context): LanProbeRound? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        if (!probeNetworkEligible(connectivity.getNetworkCapabilities(network))) return null
        val properties = connectivity.getLinkProperties(network) ?: return null
        val link = properties.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        // IPv4 enumeration is optional. IPv6-only LANs still need the observed owner so NSD can
        // discover trusted ULA endpoints. A changed/appearing IPv4 subnet invalidates this round.
        fun sameSubnet(current: LinkProperties?): Boolean {
            if (current == null) return false
            return if (link == null) current.linkAddresses.none {
                it.address is Inet4Address && !it.address.isLoopbackAddress
            } else current.linkAddresses.any {
                it.address == link.address && it.prefixLength == link.prefixLength
            }
        }
        lateinit var callback: ConnectivityManager.NetworkCallback
        val round = LanProbeRound(network.socketFactory, eligible = {
            connectivity.activeNetwork == network &&
                probeNetworkEligible(connectivity.getNetworkCapabilities(network)) &&
                sameSubnet(connectivity.getLinkProperties(network))
        }, unregister = { runCatching { connectivity.unregisterNetworkCallback(callback) }; Unit },
            hosts = link?.let { probeCandidates(it.address.hostAddress.orEmpty(), it.prefixLength) }.orEmpty())
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                if (available != network) round.invalidate()
            }
            override fun onLost(lost: Network) {
                if (lost == network) round.invalidate()
            }
            override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
                if (changed != network || !probeNetworkEligible(capabilities)) round.invalidate()
            }
            override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
                if (changed != network || !sameSubnet(properties)) round.invalidate()
            }
        }
        try {
            // This public API is available before minSdk 28. Its callback observes the app's
            // default (including VPN), and must remain registered through the visible scan.
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            round.close()
            return null // Unsupported/refused observation must not start an unowned probe.
        }
        if (!round.isCurrent()) { round.close(); return null }
        return round
    }
}

internal fun probeNetworkEligible(capabilities: NetworkCapabilities?): Boolean = capabilities != null &&
    (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

/**
 * Discovery and the direct probe as one answer. A finished probe has asked every host on the network,
 * so it settles the question as soon as it has found anyone; with nothing found, discovery's own
 * result stands, as it may reach a server the probe could not. Discovery's names are kept for servers
 * both found.
 */
internal fun combineDiscovery(nsd: DiscoverySnapshot, probe: List<DiscoveredServer>?, allowed: Boolean = true,
    networkCurrent: Boolean = true): DiscoverySnapshot {
    // No running probe and no usable discoveries while local-network permission is missing.
    if (!allowed) return DiscoverySnapshot(DiscoveryStatus.IDLE)
    // Network invalidation is terminal, not an empty successful probe that can revive old NSD.
    if (!networkCurrent) return DiscoverySnapshot(DiscoveryStatus.UNAVAILABLE)
    val servers = (nsd.servers + probe.orEmpty()).distinctBy { it.origin }
    return when {
        probe == null -> DiscoverySnapshot(DiscoveryStatus.SEARCHING, servers, nsd.unresolvedCount)
        probe.isNotEmpty() -> DiscoverySnapshot(DiscoveryStatus.COMPLETE, servers, 0)
        nsd.status == DiscoveryStatus.SEARCHING -> DiscoverySnapshot(DiscoveryStatus.SEARCHING, servers, nsd.unresolvedCount)
        else -> DiscoverySnapshot(DiscoveryStatus.COMPLETE, servers, nsd.unresolvedCount)
    }
}
