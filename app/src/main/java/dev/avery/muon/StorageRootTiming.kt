package dev.avery.muon

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Fixed cumulative diagnostic counters, no identities/paths/text/history. Root timings include
 * their CARD/PHONE volume timing; LEGACY_CARD_VOLUME and OWNED_CARD_VOLUME are separate volume-only observations. MIXED_ROUTE wraps route journals/availability and overlaps
 * nested root timing. Do not add overlapping totals. Only first/every64th observations emit,
 * at most32 events per process, then even diagnostic clock calls stop. Actions never run under
 * this observer's monitor. Clock/log failures cannot change the action's result or exception. */
internal class StorageRootTiming private constructor(private val allowance:AtomicInteger,
    private val clock:()->Long,private val emit:(Event)->Unit) {
    enum class Phase { PHONE_ROOT,PHONE_VOLUME,CARD_ROOT,CARD_VOLUME,LEGACY_CARD_VOLUME,OWNED_CARD_VOLUME,MIXED_ROUTE }
    data class Event(val phase:Phase,val count:Long,val totalMs:Long,val failures:Long)
    private val count=LongArray(Phase.entries.size)
    private val elapsed=LongArray(Phase.entries.size)
    private val failures=LongArray(Phase.entries.size)
    private fun now():Long?=runCatching {clock().coerceAtLeast(0)}.getOrNull()
    private fun add(a:Long,b:Long)=if(b>Long.MAX_VALUE-a) Long.MAX_VALUE else a+b
    fun <T> measure(phase:Phase,action:()->T):T {
        if(allowance.get()<=0) return action()
        val start=now() ?: return action()
        var returned=false
        try {return action().also {returned=true}}
        finally {
            val end=now()
            if(end!=null) {
                val event=synchronized(this) {
                    val i=phase.ordinal
                    count[i]=add(count[i],1);elapsed[i]=add(elapsed[i],(end-start).coerceAtLeast(0))
                    if(!returned) failures[i]=add(failures[i],1)
                    if(count[i]!=1L && count[i]%64!=0L) null
                    else {
                        var allowed=false
                        while(true) {
                            val left=allowance.get();if(left<=0) break
                            if(allowance.compareAndSet(left,left-1)) {allowed=true;break}
                        }
                        if(allowed) Event(phase,count[i],elapsed[i],failures[i]) else null
                    }
                }
                if(event!=null) runCatching {emit(event)}
            }
        }
    }
    companion object {
        private val remaining=AtomicInteger(32)
        fun begin(enabled:Boolean,allowance:AtomicInteger=remaining,clock:()->Long=SystemClock::elapsedRealtime,
            emit:(Event)->Unit={e ->android.util.Log.d("MuonRootTiming",
                "phase=${e.phase} count=${e.count} totalMs=${e.totalMs} failures=${e.failures}")}
        ):StorageRootTiming?=if(enabled && allowance.get()>0) StorageRootTiming(allowance,clock,emit) else null
    }
}
