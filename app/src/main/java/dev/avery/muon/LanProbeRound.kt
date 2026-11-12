package dev.avery.muon

import java.io.Closeable
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request

/** A scan owns its calls and pool, but never the shared Transport dispatcher or executor. */
internal class LanProbeRound(
    socketFactory: SocketFactory,
    private val eligible: () -> Boolean,
    private val unregister: () -> Unit = {},
    base: OkHttpClient = Transport.client,
    private val hosts: List<String> = emptyList(),
) : Closeable {
    // OkHttp 4.12 pool eligibility does not compare socketFactory. A new factory alone could
    // therefore reuse an unbound Transport connection. Keep this round's pool separate and empty
    // between requests; newBuilder still shares the dispatcher, which must not be shut down.
    internal val client = base.newBuilder().socketFactory(socketFactory)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .connectTimeout(700, TimeUnit.MILLISECONDS).readTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2500, TimeUnit.MILLISECONDS).build()
    private val lock = Any()
    private val calls = mutableSetOf<Call>()
    private val invalidation = CompletableDeferred<Unit>()
    @Volatile private var invalid = false
    private var closed = false

    fun invalidate() {
        val active = synchronized(lock) {
            if (invalid) return
            invalid = true
            calls.toList()
        }
        invalidation.complete(Unit)
        active.forEach { it.cancel() }
    }

    /** A loss is terminal, even if that network later becomes eligible again. */
    fun isCurrent(): Boolean {
        if (!invalid && !runCatching(eligible).getOrDefault(false)) invalidate()
        return !invalid
    }

    suspend fun awaitInvalidation() = invalidation.await()

    suspend fun find(hosts: List<String> = this.hosts, concurrency: Int = 48): List<DiscoveredServer> =
        withContext(Dispatchers.IO) {
            val lanes = Dispatchers.IO.limitedParallelism(concurrency)
            val found = coroutineScope {
                hosts.map { host -> async(lanes) { answer(host) } }.awaitAll().filterNotNull()
            }
            if (isCurrent()) found else emptyList()
        }

    internal suspend fun answer(host: String): DiscoveredServer? {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return null
        val endpoint = ServerEndpoint.parse(host)
        val call = client.newCall(Request.Builder().url(endpoint.url("/api1/version")).build())
        synchronized(lock) {
            if (invalid) { call.cancel(); return null }
            calls.add(call)
        }
        return try {
            // Recheck after queueing/creating the Call and before it can open a socket. A callback
            // racing this check also sees the registered Call and cancels only this round's work.
            if (!isCurrent()) return null
            val server = call.readCancellable { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null || source.request(4097)) return@readCancellable null
                if (parseTauonJson(source.readUtf8()).optInt("version") != 1) return@readCancellable null
                DiscoveredServer("Tauon", endpoint.origin)
            }
            if (isCurrent()) server else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { synchronized(lock) { calls.remove(call) } }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        invalidate()
        try { unregister() }
        finally { client.connectionPool.evictAll() }
    }
}
