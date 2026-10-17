package dev.avery.muon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock

/** Diagnostic-only constant labels and monotonic times: no song IDs, private text, error messages or history. */
internal class SavedStartupTiming(val enabled: Boolean,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val emit: (Event) -> Unit = {}) {
    enum class Phase { PREPARATION, ADMISSION, ROUTE_PHONE, ROUTE_CARD, OPEN_PHONE, OPEN_CARD, FIRST_READ_PHONE, FIRST_READ_CARD, READY }
    enum class Outcome { OK, REFUSED, FAILED, CANCELLED }
    data class Event(val phase: Phase, val outcome: Outcome, val milliseconds: Long, val elapsed: Long)
    class Token internal constructor(internal val phase: Phase, internal val start: Long) { internal var ended = false }
    fun begin(phase: Phase): Token? = if (enabled) Token(phase, clock()) else null
    fun end(token: Token?, outcome: Outcome = Outcome.OK) {
        if (token == null || !enabled) return
        synchronized(token) {
            if (token.ended) return
            token.ended = true
            val now = clock()
            // A failing diagnostic sink must never change playback/cancellation behavior.
            runCatching { emit(Event(token.phase, outcome, (now - token.start).coerceAtLeast(0), now)) }
        }
    }
    companion object {
        val DISABLED = SavedStartupTiming(false)
        fun forContext(context: Context): SavedStartupTiming =
            if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) DISABLED
            else SavedStartupTiming(true, emit = { event ->
                android.util.Log.d("MuonSavedTiming", "${event.phase} ${event.outcome} durationMs=${event.milliseconds} elapsedMs=${event.elapsed}")
            })
    }
}
