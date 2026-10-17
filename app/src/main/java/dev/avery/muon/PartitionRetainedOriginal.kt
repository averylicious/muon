@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.exoplayer.offline.Download
import java.io.IOException
import java.security.MessageDigest

/** Fixed identity of one bounded STOPPED/FAILED original row, never a completed-copy receipt.
 * Request and progress are consumed transiently; no payload, native index or audio is retained.
 * This observation alone grants no file/row mutation, migration or manager command authority. */
internal data class PartitionRetainedOriginal private constructor(val key:String,val state:Int,
    val contentLength:Long,val bytesDownloaded:Long,val identity:String) {
    companion object {
        fun from(download:Download):PartitionRetainedOriginal? {
            if(download.state!=Download.STATE_STOPPED && download.state!=Download.STATE_FAILED) return null
            val key=partitionFullRequestKey(download.request,false)
            if(download.contentLength<C.LENGTH_UNSET || download.bytesDownloaded<0 ||
                !download.percentDownloaded.isFinite() || download.percentDownloaded !in -1f..100f ||
                download.state==Download.STATE_STOPPED && download.failureReason!=Download.FAILURE_REASON_NONE ||
                download.state==Download.STATE_FAILED && download.failureReason==Download.FAILURE_REASON_NONE)
                throw IOException("Retained original has unsupported state or progress")
            val hash=MessageDigest.getInstance("SHA-256")
            hash.update("muon-retained-original-v1".toByteArray(Charsets.US_ASCII))
            for(value in listOf(download.state.toLong(),download.contentLength,download.startTimeMs,
                download.updateTimeMs,download.stopReason.toLong(),download.failureReason.toLong(),
                download.bytesDownloaded,download.percentDownloaded.toBits().toLong()))
                for(shift in 56 downTo 0 step 8) hash.update((value ushr shift).toByte())
            hash.update(partitionSaveRequestDigest(download.request))
            val identity=hash.digest().joinToString("") {"%02x".format(it)}
            return PartitionRetainedOriginal(key,download.state,download.contentLength,download.bytesDownloaded,identity)
        }
    }
}
