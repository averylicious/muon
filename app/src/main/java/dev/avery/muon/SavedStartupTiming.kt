package dev.avery.muon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Explicit diagnostic/timing builds only. Fixed labels/times, no private text or history.
 * Production factories share at most32 attempted operation tokens per process; once exhausted,
 * new operations call no diagnostic clock/sink. Clock/sink failure cannot affect real work. */
internal class SavedStartupTiming(val enabled: Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val allowance: AtomicInteger = AtomicInteger(32),
    private val emit: (Event) -> Unit = {}) {
    enum class Phase { PREPARATION, ADMISSION, ROUTE_PHONE, ROUTE_CARD, OPEN_PHONE, OPEN_CARD, FIRST_READ_PHONE, FIRST_READ_CARD, READY }
    enum class Outcome { OK, REFUSED, FAILED, CANCELLED }
    data class Event(val phase: Phase, val outcome: Outcome, val milliseconds: Long, val elapsed: Long)
    class Token internal constructor(internal val phase: Phase, internal val start: Long) { internal var ended = false }
    private fun now():Long?=runCatching {clock().coerceAtLeast(0)}.getOrNull()
    fun begin(phase: Phase): Token? {
        if(!enabled) return null
        while(true) {
            val left=allowance.get();if(left<=0) return null
            if(allowance.compareAndSet(left,left-1)) break
        }
        return now()?.let {Token(phase,it)}
    }
    fun end(token: Token?, outcome: Outcome = Outcome.OK) {
        if (token == null || !enabled) return
        synchronized(token) {
            if (token.ended) return
            token.ended = true
            val finished = now()?.coerceAtLeast(token.start) ?: return
            runCatching { emit(Event(token.phase, outcome, finished - token.start, finished)) }
        }
    }
    companion object {
        private val processAllowance=AtomicInteger(32)
        val DISABLED = SavedStartupTiming(false)
        fun forContext(context: Context,timingBuild:Boolean=BuildConfig.STORAGE_TIMINGS,
            allowance:AtomicInteger=processAllowance): SavedStartupTiming =
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0 && !timingBuild) DISABLED
            else SavedStartupTiming(true, allowance=allowance, emit = { event ->
                android.util.Log.d("MuonSavedTiming", "${event.phase} ${event.outcome} durationMs=${event.milliseconds} elapsedMs=${event.elapsed}")
            })
    }
}
