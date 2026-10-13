@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import java.io.IOException

/** The actual native index under the retained/startup views. Every returned cursor keeps the store
 * permit until its observable close, including EOF/early exit. Native scalar/write APIs retain their
 * permit for the synchronous call; failure cannot prove opaque internal cleanup, so it quarantines
 * the exact call/index instead of granting a live migration or retry. No schema writes or row filter.
 */
internal class StorageDownloadIndex(private val actual:WritableDownloadIndex,
    private val barrier:SavedStorageBarrier,private val onUncertain:()->Unit={}):WritableDownloadIndex {
    private class CallOwner(val index:WritableDownloadIndex,val operation:Any)
    private fun uncertain(lease:SavedStorageBarrier.Lease,owner:Any,failure:Throwable) {
        lease.quarantine(owner)
        try { onUncertain() } catch(callback:Throwable) { if(callback!==failure) failure.addSuppressed(callback) }
    }
    private fun <T> call(operation:()->T):T {
        val lease=barrier.shared(); var unknown=false
        try { lease.check(); return operation().also { lease.check() } }
        catch(failure:Throwable) {
            unknown=true; uncertain(lease,CallOwner(actual,operation),failure)
            throw IOException("Saved index operation failed; restart before retrying",failure)
        } finally { if(!unknown) lease.close() }
    }
    override fun getDownload(id:String):Download?=call { actual.getDownload(id) }
    override fun putDownload(download:Download)=call { actual.putDownload(download) }
    override fun removeDownload(id:String)=call { actual.removeDownload(id) }
    override fun setDownloadingStatesToQueued()=call { actual.setDownloadingStatesToQueued() }
    override fun setStatesToRemoving()=call { actual.setStatesToRemoving() }
    override fun setStopReason(stopReason:Int)=call { actual.setStopReason(stopReason) }
    override fun setStopReason(id:String,stopReason:Int)=call { actual.setStopReason(id,stopReason) }
    override fun getDownloads(vararg states:Int):DownloadCursor {
        val lease=barrier.shared()
        return try { lease.check(); Cursor(actual.getDownloads(*states),lease) }
        catch(failure:Throwable) {
            uncertain(lease,CallOwner(actual,states.copyOf()),failure)
            throw IOException("Saved index cursor construction failed; restart before retrying",failure)
        }
    }
    private inner class Cursor(private val actualCursor:DownloadCursor,
        private val lease:SavedStorageBarrier.Lease):DownloadCursor {
        private var closed=false
        private var closing=false
        private var unknown=false
        @Synchronized private fun <T> read(operation:()->T):T {
            if(closed || closing || unknown) throw IOException("Saved index cursor is closed or uncertain")
            lease.check()
            return operation().also { lease.check() }
        }
        override fun getDownload():Download=read { actualCursor.download }
        override fun getCount():Int=read { actualCursor.count }
        override fun getPosition():Int=read { actualCursor.position }
        override fun moveToPosition(position:Int):Boolean=read { actualCursor.moveToPosition(position) }
        @Synchronized override fun isClosed():Boolean=closed
        @Synchronized override fun close() {
            if(closed) return
            if(unknown || closing) { unknown=true; throw IOException("Saved index cursor close is uncertain") }
            closing=true
            try {
                actualCursor.close()
                if(unknown) throw IOException("Saved index cursor close was reentered")
                closed=true
                lease.close()
            } catch(failure:Throwable) {
                unknown=true; uncertain(lease,this,failure)
                throw IOException("Saved index cursor close is uncertain; restart before retrying",failure)
            } finally { closing=false }
        }
    }
}
