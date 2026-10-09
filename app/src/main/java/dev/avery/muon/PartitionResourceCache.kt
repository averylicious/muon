@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.util.NavigableSet

/**
 * Per-resource admission budgets for one partition (#253). Native spans and migration's coalesced
 * ranges are different limits; a valid copy can still exceed this admission policy. Refuse rather than
 * truncate. These bound retained partition growth, not measured heap or a legacy index already opened.
 */
internal data class PartitionResourceLimits(
    val keyBytes: Int = MIGRATION_KEY_BYTES,
    val metadataBytes: Long = MIGRATION_METADATA_BYTES.toLong(),
    val metadataFields: Int = MIGRATION_METADATA_FIELDS,
    /** Native spans (one file each), committed plus reserved by outstanding startFile calls. */
    val spans: Int = MIGRATION_RANGES,
    /** Write locks (hole spans) held at once. */
    val holes: Int = 4,
    /** startFile results not yet committed or released with their hole. */
    val files: Int = 4,
    val listeners: Int = 4,
) {
    init {
        require(keyBytes in 1..MIGRATION_KEY_BYTES && metadataBytes in 0..MIGRATION_METADATA_BYTES.toLong())
        require(metadataFields in 0..MIGRATION_METADATA_FIELDS && spans in 1..MIGRATION_RANGES)
        require(holes in 1..PARTITION_ACTIVE_LEASES && files in 1..PARTITION_ACTIVE_LEASES && listeners in 0..PARTITION_ACTIVE_LEASES)
    }
}

/**
 * UNWIRED (#253): a [Cache] for ONE exact resource key over an independently opened [SimpleCache] that the
 * caller owns exclusively. No production caller exists; legacy caches and every current caller are
 * unchanged. Routing, lease lifecycle and the migration journal are later integration.
 *
 * Ownership contract, which this class cannot verify and the caller must keep:
 * - The [SimpleCache] was opened by the partition owner for this partition only, with a NoOpCacheEvictor,
 *   and no other code holds or uses it. This class never adopts a directory: [open] refuses a cache that
 *   already names another key, but inspecting it uses the supported key-set copy, so it cannot bound or
 *   make safe an already-open legacy or untrusted index. Never wrap the legacy cache.
 * - Readers of cached (non-hole) spans have no release event in the Cache API, so [release] cannot know
 *   about them. The caller must keep its own reader lease until every reader is closed.
 * - Listener and evictor callbacks may read but must not start or commit writes on this instance.
 *
 * Every call naming another key, a foreign file, or a span or hole this instance did not hand out is
 * refused before the native cache sees it. Every budget is checked before the native mutation. Refusals
 * delete, truncate or rewrite nothing. A native failure part-way through a mutation leaves the state
 * unknown: the instance then refuses every further mutation ([uncertain]) until it is re-opened and
 * re-admitted. Bookkeeping is fixed-size slots; nothing accumulates per call.
 *
 * All state is guarded by the SimpleCache's own monitor, which every SimpleCache method also takes, so
 * callbacks running inside the native cache can never deadlock against a second lock.
 */
internal class PartitionResourceCache private constructor(
    private val cache: SimpleCache,
    val key: String,
    private val limits: PartitionResourceLimits,
    private var spans: Int,
    /** A resource that held metadata but no bytes when admitted: Media3 would drop it on a hole release. */
    private var bareMetadata: Boolean,
) : Cache {
    private class FileSlot(val file: File, val position: Long, val length: Long, val hole: CacheSpan)

    private val holes = arrayOfNulls<CacheSpan>(limits.holes)
    private var holesReserved = 0
    private val files = arrayOfNulls<FileSlot>(limits.files)
    private class ListenerSlot(val client:Cache.Listener,val bridge:Cache.Listener)
    private val listeners = arrayOfNulls<ListenerSlot>(limits.listeners)
    private var callbackDepth=0
    private var released = false

    /** A native mutation failed part-way; no further mutation is admitted on this instance. */
    @Volatile var uncertain = false
        private set

    companion object {
        /**
         * Admits [cache] as the partition for exactly [key], or throws without changing anything. Refuses
         * another key (including metadata-only or locked ones), unknown metadata, and any state already
         * over [limits].
         */
        @Throws(IOException::class)
        fun open(cache: SimpleCache, key: String, limits: PartitionResourceLimits = PartitionResourceLimits()): PartitionResourceCache =
            synchronized(cache) {
                if (key.length.toLong() * 2 > limits.keyBytes) throw IOException("Partition key exceeds its budget")
                cache.checkInitialization()
                for (name in cache.keys) if (name != key) throw IOException("Partition holds another resource")
                val metadata = measured(cache.getContentMetadata(key), limits)
                val count = cache.getCachedSpans(key).size
                if (count > limits.spans) throw IOException("Partition resource is too fragmented")
                PartitionResourceCache(cache, key, limits, count, count == 0 && metadata.entrySet().isNotEmpty())
            }

        /** The metadata as Media3 stores it, within budget; anything else is refused unchanged. */
        private fun measured(metadata: ContentMetadata, limits: PartitionResourceLimits): DefaultContentMetadata {
            val known = metadata as? DefaultContentMetadata ?: throw IOException("Unknown cache metadata implementation")
            var fields = 0
            var bytes = 0L
            for ((name, value) in known.entrySet()) {
                if (++fields > limits.metadataFields) throw IOException("Partition metadata has too many fields")
                bytes += name.length.toLong() * 2 + value.size
                if (bytes > limits.metadataBytes) throw IOException("Partition metadata exceeds its budget")
            }
            return known
        }
    }

    // ---- reads: exact key only, no mutation ----

    override fun getUid(): Long = synchronized(cache) { live(); cache.uid }

    override fun getKeys(): Set<String> = synchronized(cache) { live(); cache.keys }

    override fun getCacheSpace(): Long = synchronized(cache) { live(); cache.cacheSpace }

    override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = synchronized(cache) { mine(key); cache.getCachedSpans(key) }

    override fun isCached(key: String, position: Long, length: Long): Boolean =
        synchronized(cache) { mine(key); cache.isCached(key, position, length) }

    override fun getCachedLength(key: String, position: Long, length: Long): Long =
        synchronized(cache) { mine(key); cache.getCachedLength(key, position, length) }

    override fun getCachedBytes(key: String, position: Long, length: Long): Long =
        synchronized(cache) { mine(key); cache.getCachedBytes(key, position, length) }

    override fun getContentMetadata(key: String): ContentMetadata = synchronized(cache) { mine(key); cache.getContentMetadata(key) }

    // ---- listeners: fixed slots ----

    override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> = synchronized(cache) {
        mine(key)
        if (listeners.any { it?.client === listener }) throw IllegalStateException("Listener already registered")
        val slot = listeners.indexOfFirst { it == null }
        if (slot < 0) throw IllegalStateException("Partition listener budget is full")
        val bridge=object:Cache.Listener {
            override fun onSpanAdded(cache:Cache,span:CacheSpan)=callback { listener.onSpanAdded(this@PartitionResourceCache,span) }
            override fun onSpanRemoved(cache:Cache,span:CacheSpan)=callback { listener.onSpanRemoved(this@PartitionResourceCache,span) }
            override fun onSpanTouched(cache:Cache,old:CacheSpan,next:CacheSpan)=callback { listener.onSpanTouched(this@PartitionResourceCache,old,next) }
        }
        val current = cache.addListener(key, bridge)
        listeners[slot] = ListenerSlot(listener,bridge)
        current
    }

    override fun removeListener(key: String, listener: Cache.Listener): Unit = synchronized(cache) {
        mine(key)
        val slot = listeners.indexOfFirst { it?.client === listener }
        if (slot >= 0) {
            cache.removeListener(key, checkNotNull(listeners[slot]).bridge)
            listeners[slot] = null
        }
    }

    // ---- write locks ----

    @Throws(InterruptedException::class, Cache.CacheException::class)
    override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan = synchronized(cache) {
        reserveHole(key, position, length)
        var span: CacheSpan? = null
        try {
            // SimpleCache waits on this same monitor, releasing it, so other threads can proceed meanwhile.
            val result: CacheSpan = cache.startReadWrite(key, position, length)
            span = result
            result
        } catch(failure:InterruptedException) { throw failure }
        catch(failure:Exception) { uncertain=true; throw failure }
        finally { recordHole(span) }
    }

    @Throws(Cache.CacheException::class)
    override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? = synchronized(cache) {
        reserveHole(key, position, length)
        var span: CacheSpan? = null
        try {
            span = cache.startReadWriteNonBlocking(key, position, length)
            span
        } catch(failure:Exception) { uncertain=true; throw failure }
        finally { recordHole(span) }
    }

    /** Before the native call: exact key, valid range, admissible state and a free hole slot. */
    private fun reserveHole(key: String, position: Long, length: Long) {
        mine(key)
        range(position,length)
        writable()
        // Acquiring and then releasing a hole on a bytes-less resource makes Media3 drop its metadata.
        if (bareMetadata) throw Cache.CacheException("Partition resource holds only metadata; it is kept read-only")
        if (holes.count { it != null } + holesReserved >= limits.holes) throw Cache.CacheException("Partition write-lock budget is full")
        holesReserved++
    }

    /** After the native call, whatever it did: a write lock is kept in a slot, anything else frees the reservation. */
    private fun recordHole(span: CacheSpan?) {
        holesReserved--
        if (span != null && !span.isCached) holes[holes.indexOfFirst { it == null }] = span
    }

    override fun releaseHoleSpan(holeSpan: CacheSpan): Unit = synchronized(cache) {
        live(); notInCallback()
        val slot = holes.indexOfFirst { it === holeSpan }
        if (slot < 0) throw IllegalArgumentException("Not a write lock this partition handed out")
        try { cache.releaseHoleSpan(holeSpan) } catch (failure: RuntimeException) { uncertain = true; throw failure }
        holes[slot] = null
        // A file started inside this lock can no longer be committed: the native cache requires the lock.
        for (i in files.indices) if (files[i]?.hole === holeSpan) files[i] = null
    }

    // ---- files ----

    @Throws(Cache.CacheException::class)
    override fun startFile(key: String, position: Long, length: Long): File = synchronized(cache) {
        mine(key)
        writable(); range(position,length)
        val hole = holes.firstOrNull { it != null && contains(it, position, length) }
            ?: throw Cache.CacheException("No write lock of this partition covers that range")
        val slot = files.indexOfFirst { it == null }
        if (slot < 0) throw Cache.CacheException("Partition pending-file budget is full")
        // Each file can become at most one native span when committed.
        if (spans + files.count { it != null } >= limits.spans) throw Cache.CacheException("Partition span budget is full")
        val file = try { cache.startFile(key, position, length) }
            catch(failure:Exception) { uncertain=true; throw failure }
        files[slot] = FileSlot(file, position, length, hole)
        file
    }

    @Throws(Cache.CacheException::class)
    override fun commitFile(file: File, length: Long): Unit = synchronized(cache) {
        live()
        val slot = files.indexOfFirst { it?.file == file }
        if (slot < 0) throw Cache.CacheException("Not a file this partition started")
        val pending = checkNotNull(files[slot])
        writable()
        if (length < 0 || length>Long.MAX_VALUE-pending.position || (pending.length != C.LENGTH_UNSET.toLong() && length > pending.length))
            throw Cache.CacheException("Committed length exceeds the started range")
        if(!file.isFile || file.canonicalFile!=file.absoluteFile || file.length()!=length)
            throw Cache.CacheException("Committed file identity or byte length differs")
        val declared = try { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
            catch (failure: RuntimeException) { uncertain = true; throw failure }
        if (length > 0 && declared != C.LENGTH_UNSET.toLong() && pending.position + length > declared)
            throw Cache.CacheException("Committed bytes would exceed the declared length")
        files[slot] = null
        try { cache.commitFile(file, length) } catch (failure: Exception) {
            uncertain = true
            recount()
            throw failure
        }
        recount()
        if (spans > limits.spans) uncertain = true
    }

    /** Native span count after a mutation; an unreadable count leaves the instance uncertain. */
    private fun recount() {
        try { spans = cache.getCachedSpans(key).size } catch (failure: RuntimeException) { uncertain = true }
    }

    // ---- removal: explicit caller decisions, never to make room ----

    override fun removeResource(key: String): Unit = synchronized(cache) {
        mine(key)
        quiet()
        try { cache.removeResource(key) } catch (failure: RuntimeException) { uncertain = true; throw failure }
        finally { recount() }
    }

    override fun removeSpan(span: CacheSpan): Unit = synchronized(cache) {
        mine(span.key)
        quiet()
        // Only the native cache's own current span object: a look-alike would make Media3 delete its file.
        val current = cache.getCachedSpans(key).firstOrNull {
            it.position == span.position && it.length == span.length && it.file != null && it.file == span.file
        } ?: throw IllegalArgumentException("Not a current span of this partition")
        // The last span's removal makes Media3 drop the resource's metadata too: use removeResource for that.
        if (spans <= 1 && (cache.getContentMetadata(key) as? DefaultContentMetadata)?.entrySet()?.isEmpty() != true)
            throw IllegalStateException("Removing the last span would drop the resource's metadata")
        try { cache.removeSpan(current) } catch (failure: RuntimeException) { uncertain = true; throw failure }
        finally { recount() }
    }

    // ---- metadata ----

    @Throws(Cache.CacheException::class)
    override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations): Unit = synchronized(cache) {
        mine(key)
        writable()
        val current = try { measured(cache.getContentMetadata(key), limits) } catch (e: IOException) { throw Cache.CacheException(e) }
        // The exact result Media3 will store, computed without storing it; every unknown field carries over.
        val after = try { measured(current.copyWithMutationsApplied(mutations), limits) } catch (e: IOException) { throw Cache.CacheException(e) }
        // Metadata with no bytes and no write lock is dropped by Media3 on the next hole release or reopen.
        if (spans == 0 && holes.all { it == null }) throw Cache.CacheException("Partition metadata needs bytes or a held write lock")
        val length = ContentMetadata.getContentLength(after)
        if (length != C.LENGTH_UNSET.toLong() && length < retainedEnd())
            throw Cache.CacheException("A declared length below the retained bytes would hide them")
        try { cache.applyContentMetadataMutations(key, mutations) } catch (failure: Exception) { uncertain = true; throw failure }
        if (bareMetadata && spans > 0) bareMetadata = false
    }

    // ---- lifecycle ----

    /** Refused while this instance still holds write locks, pending files or listeners. */
    override fun release(): Unit = synchronized(cache) {
        if (released) return@synchronized
        notInCallback()
        if (holes.any { it != null } || holesReserved > 0 || files.any { it != null } || listeners.any { it != null })
            throw IllegalStateException("Partition still has write locks, pending files or listeners")
        released = true
        cache.release()
    }

    // ---- checks ----

    private fun live() { check(!released) { "Partition was released" } }

    private fun mine(name: String) {
        live()
        if (name != key) throw IllegalArgumentException("Not this partition's resource")
    }

    private fun writable() { notInCallback(); if (uncertain) throw Cache.CacheException("Partition state is uncertain; re-open and re-admit it") }

    private fun quiet() {
        notInCallback()
        if (uncertain) throw IllegalStateException("Partition state is uncertain; re-open and re-admit it")
        if (holes.any { it != null } || holesReserved > 0 || files.any { it != null })
            throw IllegalStateException("Partition has writes in progress")
    }

    private fun range(position:Long,length:Long) {
        if(position<0 || (length!=C.LENGTH_UNSET.toLong() && (length<=0 || length>Long.MAX_VALUE-position)))
            throw Cache.CacheException("Invalid or overflowing cache range")
    }
    private fun notInCallback() {
        if(callbackDepth>0) throw IllegalStateException("Partition callbacks cannot mutate or release storage")
    }
    private fun callback(block:()->Unit) {
        callbackDepth++
        try { block() } finally { callbackDepth-- }
    }

    private fun contains(hole: CacheSpan, position: Long, length: Long): Boolean {
        if (position < hole.position) return false
        if (hole.isOpenEnded) return true
        if (length == C.LENGTH_UNSET.toLong()) return false
        return length >= 0 && position - hole.position <= hole.length - length
    }

    /** End of the last retained byte, walking coalesced ranges; bounded by the admitted span count. */
    private fun retainedEnd(): Long {
        var position = 0L
        var end = 0L
        var steps = 0
        while (position < Long.MAX_VALUE) {
            if (++steps > 2 * limits.spans + 2) throw Cache.CacheException("Partition extent cannot be established")
            val block = cache.getCachedLength(key, position, Long.MAX_VALUE - position)
            if (block == 0L || block == Long.MIN_VALUE) throw Cache.CacheException("Partition extent cannot be established")
            val length = if (block < 0) -block else block
            if(length>Long.MAX_VALUE-position) throw Cache.CacheException("Partition extent overflows")
            if (block > 0) end = position + length
            position += length
        }
        return end
    }
}
