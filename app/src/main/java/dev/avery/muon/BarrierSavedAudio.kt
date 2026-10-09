@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Prepared adapter for MixedSavedAudio (or a borrowed legacy test reader). A permit lasts through
 * real child close, including after EOF and failed open. The caller still owns native/journal
 * lifetimes. Actual legacy shelves and prepared partition shelves share their store admission gate.
 * Native construction and asynchronous mutation ownership still require separate integration.
 */
internal class BarrierSavedAudio(private val audio:SavedAudio,private val barrier:SavedStorageBarrier):SavedAudio {
    private fun <T> operation(work:(SavedStorageBarrier.Lease)->T):T {
        val permit=barrier.shared()
        var quarantined=false
        try { permit.check(); return work(permit).also { permit.check() } }
        catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain) {
                permit.quarantine(failure); quarantined=true
            }
            throw failure
        }
        finally { if(!quarantined) permit.close() }
    }
    override fun contains(key:String)=operation { audio.contains(key) }
    override fun inspect(key:String)=operation { audio.inspect(key) }
    override fun forEachKey(visit:(String)->Boolean)=operation { permit ->
        audio.forEachKey { key ->
            // A nested destructive request cannot get exclusive ownership during this callback.
            permit.check(); visit(key).also { permit.check() }
        }
    }
    override val source:DataSource.Factory=DataSource.Factory { Reader() }
    private enum class Phase { Idle,Opening,Open,Reading,Listener,Closing,Uncertain }
    private inner class Reader:DataSource {
        private var phase=Phase.Idle
        private var child:DataSource?=null
        private var permit:SavedStorageBarrier.Lease?=null
        private var lost=false
        private val listeners=arrayOfNulls<TransferListener>(4)
        @Synchronized override fun addTransferListener(listener:TransferListener) {
            check(phase==Phase.Idle || phase==Phase.Open) { "Saved storage reader busy or uncertain" }
            if(listeners.any { it===listener }) return
            val slot=listeners.indexOfFirst { it==null }; check(slot>=0) { "Saved listener budget full" }
            if(phase==Phase.Open) {
                phase=Phase.Listener
                try { checked(); child!!.addTransferListener(listener); checked() }
                catch(failure:Throwable) { retainUnknown(failure); lost=true; throw failure }
                finally { if(phase!=Phase.Uncertain) phase=Phase.Open }
            }
            listeners[slot]=listener
        }
        private fun retainUnknown(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain) {
                phase=Phase.Uncertain; permit?.quarantine(failure)
            }
        }
        private fun checked() {
            if(lost) throw IOException("Saved storage reader lost availability; close before reopening")
            requireNotNull(permit).check()
        }
        @Synchronized override fun open(spec:DataSpec):Long {
            check(phase==Phase.Idle) { "Saved storage reader already open or uncertain" }
            phase=Phase.Opening
            try {
                permit=barrier.shared(); checked()
                child=audio.source.createDataSource()
                for(listener in listeners) if(listener!=null) child!!.addTransferListener(listener)
                checked(); val length=child!!.open(spec); checked()
                phase=Phase.Open; return length
            } catch(failure:Throwable) {
                if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain) {
                    phase=Phase.Uncertain; permit?.quarantine(failure)
                } else try { finish() } catch(cleanup:Throwable) { if(cleanup!==failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        @Synchronized override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            check(phase==Phase.Open) { "Saved storage reader not open or busy" }
            phase=Phase.Reading
            try { checked(); val count=child!!.read(buffer,offset,length); checked(); return count }
            catch(failure:Throwable) { retainUnknown(failure); lost=true; throw failure }
            finally { if(phase!=Phase.Uncertain) phase=Phase.Open }
        }
        @Synchronized override fun getUri():Uri?=if(lost || phase==Phase.Idle || phase==Phase.Uncertain) null else child?.uri
        @Synchronized override fun getResponseHeaders():Map<String,List<String>> =
            if(lost || phase==Phase.Idle || phase==Phase.Uncertain) emptyMap() else child?.responseHeaders.orEmpty()
        @Synchronized override fun close() {
            if(phase==Phase.Idle) return
            if(phase==Phase.Uncertain) throw IOException("Saved storage close uncertain; ownership retained")
            check(phase==Phase.Open) { "Saved storage reader operation active" }
            finish()
        }
        private fun finish() {
            phase=Phase.Closing
            try { child?.close() }
            catch(failure:Throwable) {
                phase=Phase.Uncertain
                permit?.quarantine(requireNotNull(child))
                throw failure
            }
            child=null; permit?.close(); permit=null; lost=false; phase=Phase.Idle
        }
    }
}
