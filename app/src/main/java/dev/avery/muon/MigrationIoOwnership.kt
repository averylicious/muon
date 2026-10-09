package dev.avery.muon

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** One synchronous migration attempt, at most two comparison readers or one reader/one output.
 * A factory must clean up its own failed creation. Unknown close keeps the actual handle, stops
 * further opens and never retries that close. The publication owner must retain this tracker and
 * its native cache instead of releasing the cache over possibly active file operations.
 */
internal class MigrationIoOwnership(private val outputs:MoveFileOutputs=MoveFileOutputs.Real,
    private val inputs:(File)->RandomAccessFile={RandomAccessFile(it,"r")}) {
    private val handles=arrayOfNulls<Closeable>(3)
    private val uncertain=BooleanArray(3)
    private var opening=false
    val quiescent:Boolean get()=handles.all { it==null }
    private fun <T:Closeable> open(create:()->T):T {
        if(opening || uncertain.any { it }) throw IOException("Migration file ownership is busy or uncertain")
        val slot=handles.indexOfFirst { it==null }
        if(slot<0) throw IOException("Migration file ownership budget is full")
        opening=true
        try { return create().also { handles[slot]=it } } finally { opening=false }
    }
    fun input(file:File):RandomAccessFile=open { inputs(file) }
    fun output(file:File):MoveFileOutput {
        val raw=open { outputs.open(file) }
        return object:MoveFileOutput {
            override fun write(buffer:ByteArray,offset:Int,length:Int)=raw.write(buffer,offset,length)
            override fun flush()=raw.flush()
            override fun sync()=raw.sync()
            override fun close()=this@MigrationIoOwnership.close(raw)
        }
    }
    fun close(handle:Closeable) {
        val slot=handles.indexOfFirst { it===handle }
        if(slot<0) return // Already proved closed.
        if(uncertain[slot]) throw MigrationIoUncertain(this,null)
        try { handle.close(); handles[slot]=null }
        catch(failure:Throwable) { uncertain[slot]=true; throw MigrationIoUncertain(this,failure) }
    }
}

/** Keeps the exact unknown handles reachable even for a standalone preparation caller. The borrowed
 * source owner must also preserve its native lifetime/barrier; this exception is not cleanup proof. */
internal class MigrationIoUncertain(val ownership:MigrationIoOwnership,cause:Throwable?):
    IOException("Migration file close remains uncertain; ownership retained",cause)
