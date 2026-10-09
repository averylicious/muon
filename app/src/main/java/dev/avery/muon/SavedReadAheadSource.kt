@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

internal const val SAVED_READ_AHEAD_BYTES=64*1024

/** Buffer ONLY already-owned saved-copy bytes, outside OfflineDataSource's actual-I/O gate. Ogg's
 * last-page scan can request one byte repeatedly: each actual refill still checks card availability,
 * while tiny extractor peeks reuse at most64KiB already read into RAM. A removed card cannot be read
 * on refill; bytes already in this bounded buffer may still be consumed (as with the player's existing
 * decoded/load buffers). No availability TTL, mount caching, upstream fallback or cache writes.
 * Live streams delegate unchanged, with no allocation/read-ahead. Seek/reopen resets the buffer;
 * range length is respected. Failure/unknown close cannot revive an old buffered open.
 */
internal class SavedReadAheadSource(private val actual:DataSource,
    private val capacity:Int=SAVED_READ_AHEAD_BYTES):DataSource {
    init { require(capacity in 1..SAVED_READ_AHEAD_BYTES) }
    private enum class Phase { Idle,Opening,Open,Reading,Listening,Closing,Uncertain }
    private var phase=Phase.Idle
    private var data:ByteArray?=null
    private var position=0
    private var end=0
    private var remaining=C.LENGTH_UNSET.toLong()
    private var failed=false
    @Synchronized override fun addTransferListener(listener:TransferListener) {
        check(phase==Phase.Idle || phase==Phase.Open)
        val prior=phase; phase=Phase.Listening
        try { actual.addTransferListener(listener) } finally { phase=prior }
    }
    @Synchronized override fun open(spec:DataSpec):Long {
        check(phase==Phase.Idle) { "Read-ahead source already open or uncertain" }
        phase=Phase.Opening; failed=false; position=0; end=0
        try {
            val length=actual.open(spec)
            if(spec.uri.scheme==SAVED_SCHEME) {
                data=ByteArray(capacity)
                remaining=when {
                    length>=0 && spec.length>=0 -> minOf(length,spec.length)
                    length>=0 -> length
                    else -> spec.length
                }
            }
            phase=Phase.Open; return length
        } catch(failure:Throwable) { failed=true; phase=Phase.Open; throw failure }
    }
    @Synchronized override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        check(phase==Phase.Open) { "Read-ahead source is not open or is busy" }
        require(offset>=0 && length>=0 && offset<=buffer.size-length)
        if(length==0) return 0
        if(failed) throw IOException("Saved read-ahead failed; close before reopening")
        phase=Phase.Reading
        try {
            val bytes=data ?: return actual.read(buffer,offset,length)
            if(position==end) {
                position=0; end=0
                if(remaining==0L) return C.RESULT_END_OF_INPUT
                val request=if(remaining<0) bytes.size else minOf(bytes.size.toLong(),remaining).toInt()
                val count=actual.read(bytes,0,request)
                if(count==C.RESULT_END_OF_INPUT) { remaining=0; return count }
                if(count<0 || count>request) throw IOException("Saved read-ahead returned invalid byte count")
                if(count==0) return 0
                end=count
                if(remaining>=0) remaining-=count
            }
            val count=minOf(length,end-position)
            System.arraycopy(bytes,position,buffer,offset,count); position+=count
            return count
        } catch(failure:Throwable) {
            failed=true; data?.fill(0); position=0; end=0
            throw failure
        } finally { phase=Phase.Open }
    }
    @Synchronized override fun getUri():Uri?=if(failed || phase==Phase.Idle || phase==Phase.Uncertain) null else actual.uri
    @Synchronized override fun getResponseHeaders():Map<String,List<String>> =
        if(failed || phase==Phase.Idle || phase==Phase.Uncertain) emptyMap() else actual.responseHeaders
    @Synchronized override fun close() {
        if(phase==Phase.Idle) return
        if(phase==Phase.Uncertain) throw IOException("Saved read-ahead close remains uncertain")
        check(phase==Phase.Open) { "Read-ahead operation already active" }
        phase=Phase.Closing
        try { actual.close(); phase=Phase.Idle; failed=false }
        catch(failure:Throwable) { phase=Phase.Uncertain; throw failure }
        finally { data?.fill(0); data=null; position=0; end=0; remaining=C.LENGTH_UNSET.toLong() }
    }
}
