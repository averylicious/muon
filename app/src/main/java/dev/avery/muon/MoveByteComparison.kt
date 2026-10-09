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
internal class MoveByteComparison(private val capacity:Int=2) {
    init { require(capacity in 1..2) }
    private class Entry { var source:DataSource?=null; var target:DataSource?=null; var uncertain=false }
    private val entries=arrayOfNulls<Entry>(capacity)
    val active:Int @Synchronized get()=entries.count { it!=null }
    @Synchronized private fun acquire():Entry {
        if(entries.any { it?.uncertain==true }) throw IOException("Move verification close uncertain; readers retained")
        val slot=entries.indexOfFirst { it==null }
        if(slot<0) throw IOException("Move verification reader budget full")
        return Entry().also { entries[slot]=it }
    }
    @Synchronized private fun finish(entry:Entry) {
        if(!entry.uncertain) {
            val slot=entries.indexOfFirst { it===entry }
            if(slot>=0) entries[slot]=null
        }
    }
    fun compare(spec:DataSpec,source:DataSource.Factory,target:DataSource.Factory,
        keepGoing:()->Boolean,unknownClose:()->Unit):Boolean {
        if(spec.length<=0) throw IOException("Move verification requires an exact positive length")
        val entry=acquire(); var failure:Throwable?=null; var matches=false
        try {
            if(!keepGoing()) throw IOException("Move verification unavailable")
            entry.source=source.createDataSource(); entry.target=target.createDataSource()
            if(entry.source===entry.target) throw IOException("Move readers must have independent ownership")
            entry.source!!.open(spec); entry.target!!.open(spec)
            val expected=ByteArray(64*1024); val actual=ByteArray(expected.size)
            var left=spec.length; matches=true
            while(left>0) {
                if(!keepGoing()) { matches=false; break }
                val count=minOf(left,expected.size.toLong()).toInt()
                if(!fully(entry.source!!,expected,count) || !fully(entry.target!!,actual,count)) { matches=false; break }
                if((0 until count).any { expected[it]!=actual[it] }) { matches=false; break }
                left-=count
            }
            if(!keepGoing()) matches=false
        } catch(caught:Throwable) { failure=caught }
        finally {
            // Attempt each real child exactly once, even failed-open/mismatch paths. Never let the
            // first close failure prevent closing the other reader or turn a match into success.
            var notified=false
            for(child in arrayOf(entry.source,entry.target?.takeUnless { it===entry.source })) if(child!=null) {
                try { child.close() }
                catch(caught:Throwable) {
                    synchronized(this) { entry.uncertain=true }
                    if(failure==null) failure=caught else if(failure!==caught) failure!!.addSuppressed(caught)
                    if(!notified) {
                        notified=true
                        try { unknownClose() } catch(notice:Throwable) {
                            if(failure!==notice) failure!!.addSuppressed(notice)
                        }
                    }
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
