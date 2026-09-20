package dev.avery.muon

import java.util.Locale

/** A discovered address has passed the same validation as manual entry; it is not authenticated. */
data class DiscoveredServer(val name: String, val origin: String)
enum class DiscoveryStatus { IDLE, SEARCHING, COMPLETE, UNAVAILABLE }
data class DiscoverySnapshot(
    val status: DiscoveryStatus,
    val servers: List<DiscoveredServer> = emptyList(),
    // Includes failed/in-flight resolutions and an overflow marker. A partial result is not unique.
    val unresolvedCount: Int = 0,
)

internal data class DiscoveryRequest<T>(val generation: Long, val id: Long, val key: String, val value: T)

/** Main-thread scan bookkeeping, isolated from NSD so delayed/lost callbacks can be tested. */
internal class DiscoveryScan<T>(private val limit: Int = 64) {
    private data class Entry<T>(val request: DiscoveryRequest<T>, var started: Boolean = false,
        var server: DiscoveredServer? = null, var finished: Boolean = false)
    private val entries = linkedMapOf<String, Entry<T>>()
    private var generation = 0L
    private var nextId = 0L
    private var overflow = false
    private var status = DiscoveryStatus.IDLE

    fun begin(): Long {
        entries.clear(); overflow = false; status = DiscoveryStatus.SEARCHING
        return ++generation
    }
    fun accepts(token: Long) = token == generation && status == DiscoveryStatus.SEARCHING
    fun found(token: Long, key: String, value: T) {
        if (!accepts(token) || key in entries) return
        if (entries.size >= limit) { overflow = true; return }
        entries[key] = Entry(DiscoveryRequest(token, ++nextId, key, value))
    }
    fun lost(token: Long, key: String) { if (accepts(token)) entries.remove(key) }
    fun nextRequest(): DiscoveryRequest<T>? {
        if (status != DiscoveryStatus.SEARCHING) return null
        val entry = entries.values.firstOrNull { !it.started } ?: return null
        entry.started = true
        return entry.request
    }
    fun resolved(request: DiscoveryRequest<T>, server: DiscoveredServer?): Boolean {
        if (!accepts(request.generation)) return false
        val entry = entries[request.key]?.takeIf { it.request.id == request.id } ?: return false
        if (entry.finished) return false
        entry.finished = true
        entry.server = server
        return true
    }
    fun finish(token: Long, unavailable: Boolean = false) {
        if (accepts(token)) status = if (unavailable) DiscoveryStatus.UNAVAILABLE else DiscoveryStatus.COMPLETE
    }
    fun cancel() { ++generation; entries.clear(); overflow = false; status = DiscoveryStatus.IDLE }
    fun snapshot() = DiscoverySnapshot(status,
        entries.values.mapNotNull { it.server }.distinctBy { it.origin },
        entries.values.count { it.server == null } + if (overflow) 1 else 0)
}

/** DNS names are case-insensitive; Android may include root/local-domain suffixes. */
internal fun normalizedDiscoveryType(value: String): String =
    value.trim().trim('.').lowercase(Locale.ROOT).removeSuffix(".local")
