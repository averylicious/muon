package dev.avery.muon

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/** Max32 fixed numeric events/process; latest event PER reader is cumulative, never sum progress
 * snapshots. Reader numbers are process-local counters, not track/server identifiers. Only explicit
 * timing/diagnostic builds enable this; clock/sink failure cannot affect real work or closure. */
internal class LegacyReaderTiming private constructor(private val reader:Int,private val started:Long,
    private val events:AtomicInteger,private val clock:()->Long,private val emit:(Event)->Unit) {
    enum class Stage { PROJECTION,OPEN,CHECKS,READ,CLOSE }
    data class Event(val reader:Int,val closed:Boolean,val failed:Boolean,val totalMs:Long,
        val projectionMs:Long,val openMs:Long,val checksMs:Long,val readMs:Long,val closeMs:Long,
        val readCalls:Long,val bytesRead:Long)
    private val elapsed=LongArray(Stage.entries.size)
    private var last=started
    private var valid=true
    private var ended=false
    private var failed=false
    private var reads=0L
    private var bytes=0L
    private fun active()=valid && !ended && events.get()>0
    private fun now():Long?=try {clock().coerceAtLeast(last).also {last=it}} catch(_:Throwable) {valid=false;null}
    private fun add(a:Long,b:Long)=if(b>Long.MAX_VALUE-a) Long.MAX_VALUE else a+b
    fun <T> measure(stage:Stage,work:()->T):T {
        val start=if(active()) now() else null
        try {return work()}
        catch(failure:Throwable) {failed=true;throw failure}
        finally {if(start!=null && active()) now()?.let {end ->
            elapsed[stage.ordinal]=add(elapsed[stage.ordinal],end-start)
        }}
    }
    fun opened() {snapshot(false)}
    fun read(count:Int) {
        if(!active()) return
        reads=add(reads,1);bytes=add(bytes,count.coerceAtLeast(0).toLong())
        if(reads and (reads-1)==0L) snapshot(false)
    }
    fun failure() {failed=true;snapshot(false)}
    fun finish(closed:Boolean) {
        if(ended) return
        if(!closed) failed=true
        snapshot(closed);ended=true
    }
    private fun snapshot(closed:Boolean) {
        if(!active()) return
        val at=now() ?: return
        if(take(events)<=0) return
        val event=Event(reader,closed,failed,at-started,elapsed[0],elapsed[1],elapsed[2],elapsed[3],elapsed[4],reads,bytes)
        runCatching {emit(event)}
    }
    companion object {
        private val readers=AtomicInteger(32)
        private val events=AtomicInteger(32)
        private fun take(budget:AtomicInteger):Int {
            while(true) {val left=budget.get();if(left<=0) return 0;if(budget.compareAndSet(left,left-1)) return left}
        }
        fun begin(enabled:Boolean=BuildConfig.DEBUG || BuildConfig.STORAGE_TIMINGS,
            readerBudget:AtomicInteger=readers,eventBudget:AtomicInteger=events,
            clock:()->Long=SystemClock::elapsedRealtime,
            emit:(Event)->Unit={e ->android.util.Log.d("MuonReaderTiming",
                "reader=${e.reader} closed=${e.closed} failed=${e.failed} totalMs=${e.totalMs} projectionMs=${e.projectionMs} openMs=${e.openMs} checksMs=${e.checksMs} readMs=${e.readMs} closeMs=${e.closeMs} readCalls=${e.readCalls} bytesRead=${e.bytesRead}")}
        ):LegacyReaderTiming? {
            if(!enabled || eventBudget.get()<=0) return null
            val reader=take(readerBudget);if(reader<=0) return null
            val start=runCatching {clock().coerceAtLeast(0)}.getOrNull() ?: return null
            return LegacyReaderTiming(reader,start,eventBudget,clock,emit)
        }
    }
}
