package dev.avery.muon

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** At most32 aggregate events per diagnostic process. No key/path/metadata/exception or history.
 * Normal Canary/Stable do not allocate a trace or call its clock/sink. Check time overlaps the
 * post-schema phase totals; it must not be added to them as another independent duration. */
internal class LegacyProjectionTiming private constructor(private val audioOnly:Boolean,private val owned:Boolean,
    private val clock:()->Long,private val emit:(Event)->Unit,start:Long) {
    enum class Phase { SCHEMA,METADATA,FILES,CENSUS }
    data class Event(val audioOnly:Boolean,val owned:Boolean,val successful:Boolean,val totalMs:Long,
        val schemaMs:Long,val metadataMs:Long,val filesMs:Long,val censusMs:Long,
        val postSchemaCheckCount:Long,val postSchemaCheckMs:Long)
    private val elapsed=LongArray(Phase.entries.size)
    private val started=start
    private var last=start
    private var phase=Phase.SCHEMA
    private var phaseStart=start
    private var checks=0L
    private var checkTime=0L
    private var ended=false
    private fun now():Long=runCatching {clock().coerceAtLeast(last)}.getOrDefault(last).also {last=it}
    private fun add(a:Long,b:Long)=if(b>Long.MAX_VALUE-a) Long.MAX_VALUE else a+b
    fun phase(next:Phase) {
        if(ended || next.ordinal!=phase.ordinal+1) return
        val at=now();elapsed[phase.ordinal]=add(elapsed[phase.ordinal],at-phaseStart)
        phase=next;phaseStart=at
    }
    fun check(action:()->Unit) {
        if(ended) {action();return}
        val at=now()
        try {action()} finally {
            if(checks<Long.MAX_VALUE) checks++
            checkTime=add(checkTime,now()-at)
        }
    }
    fun finish(successful:Boolean) {
        if(ended) return
        ended=true
        val at=now();elapsed[phase.ordinal]=add(elapsed[phase.ordinal],at-phaseStart)
        val event=Event(audioOnly,owned,successful,at-started,elapsed[0],elapsed[1],elapsed[2],elapsed[3],checks,checkTime)
        // Diagnostics cannot turn success into failure or replace the original I/O/cancellation.
        runCatching {emit(event)}
    }
    companion object {
        private val remaining=AtomicInteger(32)
        fun begin(enabled:Boolean=BuildConfig.DEBUG || BuildConfig.STORAGE_TIMINGS,audioOnly:Boolean=true,owned:Boolean=false,
            allowance:AtomicInteger=remaining,clock:()->Long=SystemClock::elapsedRealtime,
            emit:(Event)->Unit={e ->android.util.Log.d("MuonLegacyTiming",
                "audio=${e.audioOnly} owned=${e.owned} successful=${e.successful} totalMs=${e.totalMs} "+
                "schemaMs=${e.schemaMs} metadataMs=${e.metadataMs} filesMs=${e.filesMs} censusMs=${e.censusMs} "+
                "postSchemaCheckCount=${e.postSchemaCheckCount} postSchemaCheckMs=${e.postSchemaCheckMs}")}
        ):LegacyProjectionTiming? {
            if(!enabled) return null
            while(true) {
                val left=allowance.get()
                if(left<=0) return null
                if(allowance.compareAndSet(left,left-1)) break
            }
            val start=runCatching {clock().coerceAtLeast(0)}.getOrNull() ?: return null
            return LegacyProjectionTiming(audioOnly,owned,clock,emit,start)
        }
    }
}
