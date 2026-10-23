package dev.avery.muon

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Diagnostic-only whole partition Move batch, ending at result submission, not UI delivery.
 * Fixed numeric phases, no per-copy history/identifiers, and at most32 attempts per process.
 * Nested verification/digest/root observations overlap these durations; never add them together.
 * Clock/log failures cannot replace the storage outcome or skip the caller's cleanup. */
internal class MoveOperationTiming private constructor(private val start:Long,
    private val clock:()->Long,private val emit:(Event)->Unit) {
    enum class Phase { SELECTION,ADMISSION,COPY,PUBLISH,COVER,RETIRE,SUBMISSION }
    data class Event(val complete:Boolean,val totalMs:Long,val selectionMs:Long,val admissionMs:Long,
        val copyMs:Long,val publishMs:Long,val coverMs:Long,val retireMs:Long,val submissionMs:Long)
    private val times=LongArray(Phase.entries.size)
    private var current=Phase.SELECTION
    private var last=start
    private var valid=true
    private var ended=false
    private fun now():Long?=try {clock().coerceAtLeast(last)} catch(_:Throwable) {valid=false;null}
    private fun add(end:Long) {
        val delta=end-last;val i=current.ordinal
        times[i]=if(delta>Long.MAX_VALUE-times[i]) Long.MAX_VALUE else times[i]+delta
        last=end
    }
    fun phase(next:Phase) {
        if(ended || !valid) return
        val end=now() ?: return
        add(end);current=next
    }
    /** Existing durable-stage callback; this observes completion, never grants storage authority. */
    fun boundary(stage:PartitionOriginalMoveStage) {
        when(stage) {
            PartitionOriginalMoveStage.Waiting -> Unit
            PartitionOriginalMoveStage.Copied -> phase(Phase.PUBLISH)
            PartitionOriginalMoveStage.Published -> phase(Phase.COVER)
            PartitionOriginalMoveStage.Pending -> phase(Phase.RETIRE)
            PartitionOriginalMoveStage.Complete -> phase(Phase.SUBMISSION)
        }
    }
    fun finish(complete:Boolean) {
        if(ended) return
        val end=if(valid) now() else null;ended=true
        if(end==null) return
        add(end)
        fun value(phase:Phase)=times[phase.ordinal]
        val event=Event(complete,end-start,value(Phase.SELECTION),value(Phase.ADMISSION),value(Phase.COPY),
            value(Phase.PUBLISH),value(Phase.COVER),value(Phase.RETIRE),value(Phase.SUBMISSION))
        runCatching {emit(event)}
    }
    companion object {
        private val remaining=AtomicInteger(32)
        fun begin(enabled:Boolean,allowance:AtomicInteger=remaining,clock:()->Long=SystemClock::elapsedRealtime,
            emit:(Event)->Unit={e ->android.util.Log.d("MuonMoveBatch",
                "complete=${e.complete} totalMs=${e.totalMs} selectionMs=${e.selectionMs} admissionMs=${e.admissionMs} copyMs=${e.copyMs} publishMs=${e.publishMs} coverMs=${e.coverMs} retireMs=${e.retireMs} submissionMs=${e.submissionMs}")}
        ):MoveOperationTiming? {
            if(!enabled) return null
            while(true) {
                val left=allowance.get();if(left<=0) return null
                if(allowance.compareAndSet(left,left-1)) break
            }
            val start=runCatching {clock().coerceAtLeast(0)}.getOrNull() ?: return null
            return MoveOperationTiming(start,clock,emit)
        }
    }
}
