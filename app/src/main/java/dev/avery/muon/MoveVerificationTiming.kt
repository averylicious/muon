package dev.avery.muon

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic-only, fixed numeric phases, at most32 attempted observations per process. No
 * keys/paths/song/server/error text or retained history. Clock/sink failures cannot change I/O.
 * Phase durations partition this observation; nested root/legacy counters overlap them. */
internal class MoveVerificationTiming private constructor(private val start:Long,
    private val clock:()->Long,private val emit:(Event)->Unit) {
    enum class Phase { ADMISSION,OPEN,METADATA,AUDIO,AFTER,CLOSE,FINAL }
    data class Event(val successful:Boolean,val totalMs:Long,val admissionMs:Long,val openMs:Long,
        val metadataMs:Long,val audioMs:Long,val afterMs:Long,val closeMs:Long,val finalMs:Long)
    private val times=LongArray(Phase.entries.size)
    private var current=Phase.ADMISSION
    private var last=start
    private var valid=true
    private var ended=false
    private fun now():Long?=try {clock().coerceAtLeast(0)} catch(_:Throwable) {valid=false;null}
    private fun add(end:Long) {
        val delta=(end-last).coerceAtLeast(0);val i=current.ordinal
        times[i]=if(delta>Long.MAX_VALUE-times[i]) Long.MAX_VALUE else times[i]+delta
        last=end
    }
    fun phase(next:Phase) {
        if(ended || !valid) return
        val end=now() ?: return
        add(end);current=next
    }
    fun finish(successful:Boolean) {
        if(ended) return
        val end=if(valid) now() else null;ended=true
        if(end==null) return
        add(end)
        fun value(phase:Phase)=times[phase.ordinal]
        val event=Event(successful,(end-start).coerceAtLeast(0),value(Phase.ADMISSION),value(Phase.OPEN),
            value(Phase.METADATA),value(Phase.AUDIO),value(Phase.AFTER),value(Phase.CLOSE),value(Phase.FINAL))
        runCatching {emit(event)}
    }
    companion object {
        private val remaining=AtomicInteger(32)
        fun begin(enabled:Boolean,allowance:AtomicInteger=remaining,clock:()->Long=SystemClock::elapsedRealtime,
            emit:(Event)->Unit={e ->android.util.Log.d("MuonMoveTiming",
                "successful=${e.successful} totalMs=${e.totalMs} admissionMs=${e.admissionMs} openMs=${e.openMs} metadataMs=${e.metadataMs} audioMs=${e.audioMs} afterMs=${e.afterMs} closeMs=${e.closeMs} finalMs=${e.finalMs}")}
        ):MoveVerificationTiming? {
            if(!enabled) return null
            while(true) {
                val left=allowance.get();if(left<=0) return null
                if(allowance.compareAndSet(left,left-1)) break
            }
            val start=runCatching {clock().coerceAtLeast(0)}.getOrNull() ?: return null
            return MoveVerificationTiming(start,clock,emit)
        }
    }
}
