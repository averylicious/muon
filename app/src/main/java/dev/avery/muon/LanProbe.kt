package dev.avery.muon

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

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
 * works wherever typing the address would. Only on Wi-Fi or Ethernet, never a carrier network.
 */
internal object LanProbe {
    private val client by lazy {
        Transport.client.newBuilder().connectTimeout(700, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS).callTimeout(2500, TimeUnit.MILLISECONDS).build()
    }

    /** This phone's IPv4 address and prefix on its local network, or null off Wi-Fi and Ethernet. */
    private fun ownAddress(context: Context): Pair<String, Int>? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val link = connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        return (link.address.hostAddress ?: return null) to link.prefixLength
    }

    suspend fun find(context: Context): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val (own, prefix) = ownAddress(context) ?: return@withContext emptyList()
        val lanes = Dispatchers.IO.limitedParallelism(48)
        coroutineScope {
            probeCandidates(own, prefix).map { host -> async(lanes) { answer(host) } }.awaitAll().filterNotNull()
        }
    }

    /** Tauon at [host], if its remote API answers there as version 1. */
    private fun answer(host: String): DiscoveredServer? = runCatching {
        val endpoint = ServerEndpoint.parse(host)
        client.newCall(Request.Builder().url(endpoint.url("/api1/version")).build()).execute().use { response ->
            val source = response.body?.source()
            if (!response.isSuccessful || source == null || source.request(4097)) return@use null
            if (JSONObject(source.readUtf8()).optInt("version") != 1) return@use null
            DiscoveredServer("Tauon", endpoint.origin)
        }
    }.getOrNull()
}

/**
 * Discovery and the direct probe as one answer. A finished probe has asked every host on the network,
 * so it settles the question as soon as it has found anyone; with nothing found, discovery's own
 * result stands, as it may reach a server the probe could not. Discovery's names are kept for servers
 * both found.
 */
internal fun combineDiscovery(nsd: DiscoverySnapshot, probe: List<DiscoveredServer>?): DiscoverySnapshot {
    val servers = (nsd.servers + probe.orEmpty()).distinctBy { it.origin }
    return when {
        probe == null -> DiscoverySnapshot(DiscoveryStatus.SEARCHING, servers, nsd.unresolvedCount)
        probe.isNotEmpty() -> DiscoverySnapshot(DiscoveryStatus.COMPLETE, servers, 0)
        nsd.status == DiscoveryStatus.SEARCHING -> DiscoverySnapshot(DiscoveryStatus.SEARCHING, servers, nsd.unresolvedCount)
        else -> DiscoverySnapshot(DiscoveryStatus.COMPLETE, servers, nsd.unresolvedCount)
    }
}
