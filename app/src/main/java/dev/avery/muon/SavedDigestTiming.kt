package dev.avery.muon

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Fixed numeric digest stages, at most 32 attempted observations per process. No identities,
 * paths, private text or history. READ includes lower-layer guards, not just physical disk I/O.
 * The stages are disjoint here; nested root/Move counters overlap and must not be added to them.
 * Ordinary builds never create an observer or read its clock. Diagnostics cannot alter work. */
internal class SavedDigestTiming private constructor(
    private val started: Long,
    private val clock: () -> Long,
    private val emit: (Event) -> Unit,
) {
    enum class Stage { BINDING, CHECKPOINT, CREATE, OPEN, READ, HASH, CLOSE }
    data class Event(val successful: Boolean, val totalMs: Long, val bindingMs: Long,
        val checkpointMs: Long, val createMs: Long, val openMs: Long, val readMs: Long,
        val hashMs: Long, val closeMs: Long)
    private val elapsed = LongArray(Stage.entries.size)
    private var last = started
    private var valid = true
    private var ended = false
    private fun now(): Long? = try {
        clock().coerceAtLeast(last).also { last = it }
    } catch (_: Throwable) { valid = false; null }

    fun <T> measure(stage: Stage, work: () -> T): T {
        val start = if (valid && !ended) now() else null
        try { return work() }
        finally {
            if (start != null && valid && !ended) now()?.let { end ->
                val index = stage.ordinal
                val delta = end - start
                elapsed[index] = if (delta > Long.MAX_VALUE - elapsed[index]) Long.MAX_VALUE
                    else elapsed[index] + delta
            }
        }
    }

    fun finish(successful: Boolean) {
        if (ended) return
        val end = if (valid) now() else null
        ended = true
        if (end == null) return
        val event = Event(successful, end - started, elapsed[0], elapsed[1], elapsed[2],
            elapsed[3], elapsed[4], elapsed[5], elapsed[6])
        runCatching { emit(event) }
    }

    companion object {
        private val remaining = AtomicInteger(32)
        fun begin(enabled: Boolean = BuildConfig.DEBUG || BuildConfig.STORAGE_TIMINGS,
            allowance: AtomicInteger = remaining, clock: () -> Long = SystemClock::elapsedRealtime,
            emit: (Event) -> Unit = { e -> android.util.Log.d("MuonDigestTiming",
                "successful=${e.successful} totalMs=${e.totalMs} bindingMs=${e.bindingMs} checkpointMs=${e.checkpointMs} createMs=${e.createMs} openMs=${e.openMs} readMs=${e.readMs} hashMs=${e.hashMs} closeMs=${e.closeMs}") },
        ): SavedDigestTiming? {
            if (!enabled) return null
            while (true) {
                val left = allowance.get()
                if (left <= 0) return null
                if (allowance.compareAndSet(left, left - 1)) break
            }
            val start = runCatching { clock().coerceAtLeast(0) }.getOrNull() ?: return null
            return SavedDigestTiming(start, clock, emit)
        }
    }
}
