package dev.avery.muon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * The played-song cache (#112, mockup 01): an Opus copy of each song as it plays, kept up to a limit so
 * recent listening also plays without Tauon. It shares the downloads' storage under its own keys, and
 * only its copies are ever evicted; downloads are never touched.
 */
internal const val PLAYED_PREFIX = "played:"

internal fun playedKey(id: String): String = PLAYED_PREFIX + id

/** 1 GB is a thousand million bytes, as Android's storage settings count. */
internal const val GIGABYTE = 1_000_000_000L

internal const val DEFAULT_CACHE_LIMIT = 2 * GIGABYTE

/** The limits offered in Settings. */
internal val CACHE_LIMITS = listOf(1 * GIGABYTE, 2 * GIGABYTE, 5 * GIGABYTE, 10 * GIGABYTE)

/** Full enough that new songs now push the oldest out: within 5% of the limit. */
internal fun cacheFull(used: Long, limit: Long): Boolean = limit > 0 && used >= limit - limit / 20

/** The played-song cache's use and limit, for Compose to read; written on the main thread. */
internal object PlayedCacheState {
    var used by mutableLongStateOf(0L)
    var limit by mutableLongStateOf(DEFAULT_CACHE_LIMIT)
}

/**
 * Least-recently-played eviction for the played-song cache alone, whole songs at a time. Spans under
 * any other key (downloads) are ignored: they neither count towards the limit nor are ever removed.
 * Called by the cache under its own lock; [resize] takes that same lock.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlayedSongEvictor(limit: Long, private val report: (Long) -> Unit) : CacheEvictor {
    @Volatile private var limit = limit
    private val spans = TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b)
    }
    private var size = 0L
    private var cache: Cache? = null

    private fun played(span: CacheSpan) = span.key.startsWith(PLAYED_PREFIX)

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        this.cache = cache
        if (key.startsWith(PLAYED_PREFIX) && length != C.LENGTH_UNSET.toLong()) evict(cache, length, keep = key)
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        if (!played(span)) return
        spans.add(span); size += span.length
        evict(cache, 0, keep = span.key)
        report(size)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (!played(span)) return
        if (spans.remove(span)) size -= span.length
        report(size)
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** A new limit; a lower one makes room at once. */
    fun resize(newLimit: Long) {
        limit = newLimit
        val cache = cache ?: return
        synchronized(cache) { evict(cache, 0, keep = null) }
    }

    /** Oldest songs first, never the one being written. */
    private fun evict(cache: Cache, required: Long, keep: String?) {
        var guard = spans.size
        while (size + required > limit && guard-- > 0) {
            val oldest = spans.firstOrNull { it.key != keep } ?: return
            cache.removeResource(oldest.key)
        }
    }
}
