@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Bounded production move-verification lifetime. A byte match is not success until BOTH readers
 * actually close. Unknown close retains the exact sources, refuses new comparisons and asks the
 * app owner to mark both shelves unavailable for destructive decisions. No cleanup retry/queue,
 * row/cover deletion, native release or recovery authority. One process Store owns this object.
 */
// Two arrays per comparison, at most two comparisons: at most 1 MiB of comparison buffers.
// A refill remains bounded; this does not change ordinary playback read-ahead or native admission.
private const val MOVE_COMPARISON_BYTES=256*1024

internal class MoveByteComparison(private val capacity:Int=2,private val barrier:SavedStorageBarrier?=null) {
    init { require(capacity in 1..2) }
    private class Entry(val permit:SavedStorageBarrier.Lease?) {
        var source:DataSource?=null; var target:DataSource?=null; var uncertain=false
        var construction:DataSource.Factory?=null; var uncertainty:Throwable?=null
    }
    private val entries=arrayOfNulls<Entry>(capacity)
    val active:Int @Synchronized get()=entries.count { it!=null }
    @Synchronized private fun acquire():Entry {
        if(entries.any { it?.uncertain==true }) throw IOException("Move verification close uncertain; readers retained")
        val slot=entries.indexOfFirst { it==null }
        if(slot<0) throw IOException("Move verification reader budget full")
        return Entry(barrier?.shared()).also { entries[slot]=it }
    }
    @Synchronized private fun finish(entry:Entry) {
        if(!entry.uncertain) {
            entry.permit?.close()
            val slot=entries.indexOfFirst { it===entry }
            if(slot>=0) entries[slot]=null
        }
    }
    fun compare(spec:DataSpec,source:DataSource.Factory,target:DataSource.Factory,
        keepGoing:()->Boolean,unknownClose:()->Unit):Boolean {
        if(spec.length<=0) throw IOException("Move verification requires an exact positive length")
        val entry=acquire(); var failure:Throwable?=null; var matches=false
        try {
            entry.permit?.check()
            if(!keepGoing()) throw IOException("Move verification unavailable")
            entry.construction=source
            entry.source=source.createDataSource(); entry.construction=null
            entry.construction=target
            entry.target=target.createDataSource(); entry.construction=null
            if(entry.source===entry.target) throw IOException("Move readers must have independent ownership")
            entry.source!!.open(spec); entry.target!!.open(spec)
            val expected=ByteArray(MOVE_COMPARISON_BYTES); val actual=ByteArray(expected.size)
            var left=spec.length; matches=true
            while(left>0) {
                entry.permit?.check()
                if(!keepGoing()) { matches=false; break }
                val count=minOf(left,expected.size.toLong()).toInt()
                if(!fully(entry.source!!,expected,count) || !fully(entry.target!!,actual,count)) { matches=false; break }
                if((0 until count).any { expected[it]!=actual[it] }) { matches=false; break }
                left-=count
            }
            entry.permit?.check()
            if(!keepGoing()) matches=false
        } catch(caught:Throwable) {
            failure=caught
            entry.uncertain=entry.construction!=null || caught is MigrationIoUncertain || caught is PartitionOwnershipUncertain
        }
        finally {
            // Attempt each real child exactly once, even failed-open/mismatch paths. Never let the
            // first close failure prevent closing the other reader or turn a match into success.
            for(child in arrayOf(entry.source,entry.target?.takeUnless { it===entry.source })) if(child!=null) {
                try { child.close() }
                catch(caught:Throwable) {
                    synchronized(this) { entry.uncertain=true }
                    if(failure==null) failure=caught else if(failure!==caught) failure!!.addSuppressed(caught)
                }
            }
            if(entry.uncertain) {
                entry.uncertainty=failure
                entry.permit?.quarantine(entry)
                try { unknownClose() } catch(notice:Throwable) {
                    if(failure==null) failure=notice else if(failure!==notice) failure!!.addSuppressed(notice)
                }
            }
            finish(entry)
        }
        failure?.let { throw it }
        return matches
    }
    private fun fully(source:DataSource,bytes:ByteArray,count:Int):Boolean {
        var done=0
        while(done<count) {
            val read=source.read(bytes,done,count-done)
            if(read<=0) return false
            done+=read
        }
        return true
    }
}
