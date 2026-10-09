@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.cache.Cache
import java.io.Closeable
import java.io.IOException

internal const val PARTITION_NATIVE_INSTANCES = 6
internal const val PARTITION_ACTIVE_LEASES = 16
internal class PartitionCacheBusy : IOException("Saved storage is busy; try again after the current operation")

/** Disabled migration foundation. A factory must return an initialized instance or clean up its own
 * failed creation. Open/close happen outside the pool lock so native callbacks cannot deadlock it.
 * Opening/closing slots count against capacity. No source/audio deletion, waiting queue or fallback.
 */
internal class PartitionCacheLeases(private val open: (String) -> Cache,
    private val capacity: Int = PARTITION_NATIVE_INSTANCES, private val leaseLimit: Int = PARTITION_ACTIVE_LEASES) : Closeable {
    init { require(capacity in 1..PARTITION_NATIVE_INSTANCES); require(leaseLimit in 1..PARTITION_ACTIVE_LEASES) }
    internal class Entry(val key: String) { var cache: Cache?=null; var pins=1; var closing=false }
    private val entries = java.util.LinkedHashMap<String,Entry>(capacity,0.75f,true)
    private var pins=0
    private var stopped=false
    private var healthy=true
    val resident: Int @Synchronized get()=entries.size
    val active: Int @Synchronized get()=pins

    /** Caller owns this pin until all its reader/writer-hole/listener uses end. Never release cache directly. */
    inner class Lease internal constructor(private val entry: Entry, val cache: Cache) : Closeable {
        private var ended=false
        override fun close() {
            val retire=synchronized(this@PartitionCacheLeases) {
                if (ended) return
                ended=true
                entry.pins--; pins--
                if (stopped && entry.pins==0 && !entry.closing) { entry.closing=true; true } else false
            }
            if (retire) release(entry)
        }
    }

    fun acquire(key: String): Lease {
        if (key.length.toLong()*2>MIGRATION_KEY_BYTES) throw IOException("Partition key exceeds memory budget")
        // Bounded contention retries; never accumulates requests while all native slots are pinned.
        repeat(3) {
            var reserved: Entry?=null
            var retiring: Entry?=null
            synchronized(this) {
                checkAdmission()
                entries[key]?.let { current ->
                    val cache=current.cache
                    if (cache==null || current.closing) throw PartitionCacheBusy()
                    current.pins++; pins++
                    return Lease(current,cache)
                }
                if (entries.size<capacity) {
                    reserved=Entry(key).also { entries[key]=it; pins++ }
                } else {
                    retiring=entries.values.firstOrNull { it.pins==0 && it.cache!=null && !it.closing }
                        ?.also { it.closing=true } ?: throw PartitionCacheBusy()
                }
            }
            retiring?.let { release(it) }
            reserved?.let { entry ->
                val cache=try { open(key) } catch (failure: Throwable) {
                    synchronized(this) { if (entries[key]===entry) entries.remove(key); pins-- }
                    throw failure
                }
                val permitted=synchronized(this) {
                    entry.cache=cache
                    if (stopped || !healthy) { entry.pins=0; pins--; entry.closing=true; false } else true
                }
                if (!permitted) { release(entry); throw IOException("Partition pool stopped during creation") }
                return Lease(entry,cache)
            }
        }
        throw PartitionCacheBusy()
    }
    private fun checkAdmission() {
        if (stopped || !healthy) throw IOException("Partition cache pool unavailable")
        if (pins>=leaseLimit) throw PartitionCacheBusy()
    }
    private fun release(entry: Entry) {
        try {
            requireNotNull(entry.cache).release()
            synchronized(this) { if (entries[entry.key]===entry) entries.remove(entry.key) }
        } catch (failure: Throwable) {
            // Unknown native release leaves its slot counted and prevents new admission. Do not open
            // replacement instances over the bound or attempt to delete files to recover capacity.
            synchronized(this) { healthy=false }
            throw failure
        }
    }
    override fun close() {
        val idle=synchronized(this) {
            stopped=true
            entries.values.filter { it.pins==0 && it.cache!=null && !it.closing }.onEach { it.closing=true }
        }
        var first: Throwable?=null
        for (entry in idle) try { release(entry) } catch (failure: Throwable) {
            if (first==null) first=failure else first?.addSuppressed(failure)
        }
        first?.let { throw it }
        // Active/opening instances retire on their last lease/factory return; never force-close them.
    }
}
