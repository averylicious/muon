package dev.avery.muon

import java.io.IOException

/** Prepared process-local exclusion, not an OS mount lock. All production participants must share
 * this instance before partition mode can be enabled. No waiting queue, forced close or recovery
 * reset: stopped admission lets known operations drain; invalidation also fails their checkpoints.
 * Unknown I/O keeps one bounded permit and its exact owner reachable until process teardown.
 */
internal class SavedStorageBarrier(private val budget:Int=16) {
    init { require(budget in 1..16) }
    private val slots=arrayOfNulls<Lease>(budget)
    private var accepting=true
    private var invalid=false
    @get:Synchronized val active:Int get()=slots.count { it!=null }
    @get:Synchronized val quiescent:Boolean get()=slots.all { it==null }
    /** Snapshot for UI/service refusal only; an actual operation still must acquire its lease. */
    @get:Synchronized val allowsShared:Boolean get()=accepting && !invalid &&
        slots.any { it==null } && slots.none { it?.exclusive==true }
    @Synchronized fun stopAdmission() { accepting=false }
    @Synchronized fun invalidate() { accepting=false; invalid=true }
    @Synchronized fun shared():Lease=acquire(false)
    @Synchronized fun exclusive():Lease=acquire(true)
    private fun acquire(exclusive:Boolean):Lease {
        if(!accepting || invalid || slots.any { it?.exclusive==true } || (exclusive && !quiescent))
            throw IOException("Saved storage is draining, exclusive or unavailable")
        val slot=slots.indexOfFirst { it==null }
        if(slot<0) throw IOException("Saved storage operation budget is full")
        return Lease(slot,exclusive).also { slots[slot]=it }
    }
    internal inner class Lease internal constructor(private val slot:Int,internal val exclusive:Boolean) {
        private var retained:Any?=null
        fun check()=synchronized(this@SavedStorageBarrier) {
            if(slots[slot]!==this || invalid || retained!=null)
                throw IOException("Saved storage operation lost its ownership")
        }
        fun quarantine(owner:Any)=synchronized(this@SavedStorageBarrier) {
            if(slots[slot]!==this) throw IOException("Saved storage permit already released")
            // Never replace a retained owner or build a chain of failures.
            if(retained==null) retained=owner
            invalid=true; accepting=false
        }
        fun close()=synchronized(this@SavedStorageBarrier) {
            if(slots[slot]!==this) return@synchronized
            if(retained!=null) throw IOException("Saved storage close uncertain; permit retained")
            slots[slot]=null
        }
    }
}
