@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheKeyFactory
import java.io.IOException

internal sealed interface PartitionReadRoute {
    data object Legacy:PartitionReadRoute
    data class Published(val record:MigrationRecord):PartitionReadRoute
    data class NewSave(val record:PartitionSaveRecord):PartitionReadRoute
}

/** Prepared mixed reader; production services do not select it yet. Ready migration wins, any
 * new-save claim blocks legacy fallback, and unmigrated/pending-migration originals remain readable.
 * Children, journals and native owner are borrowed. Their real shared writer/removal/eviction/volume
 * barrier is still mandatory; snapshots before/after I/O are not an OS mount or mutation lock.
 * No global key set is built: inventory visits each backend once and filters by the current route.
 * Legacy's own index/key enumeration remains unbounded. No automatic cleanup, upstream or write.
 */
internal class MixedSavedAudio(private val owner:PartitionNativeOwner,
    private val legacy:SavedAudio,private val published:PartitionPublishedAudio,
    private val completed:PartitionCompletedAudio):SavedAudio {
    private fun route(key:String)=owner.savedReadRoute(key)
    private fun audio(route:PartitionReadRoute):SavedAudio=when(route) {
        PartitionReadRoute.Legacy -> legacy
        is PartitionReadRoute.Published -> published
        is PartitionReadRoute.NewSave -> completed
    }
    private fun unchanged(key:String,expected:PartitionReadRoute) {
        if(route(key)!=expected) throw IOException("Mixed saved-copy route changed; close before reopening")
    }
    override fun contains(key:String):Boolean {
        val expected=route(key)
        return audio(expected).contains(key).also { unchanged(key,expected) }
    }
    override fun inspect(key:String):SavedAudioState {
        val expected=route(key)
        return audio(expected).inspect(key).also { unchanged(key,expected) }
    }
    override fun forEachKey(visit:(String)->Boolean) {
        var more=true
        for(backend in listOf<SavedAudio>(published,completed,legacy)) {
            if(!more) return
            backend.forEachKey { key ->
                val expected=route(key)
                if(audio(expected)===backend) {
                    more=visit(key)
                    unchanged(key,expected)
                }
                more
            }
        }
    }
    override val source:DataSource.Factory=DataSource.Factory { Reader() }
    private enum class Phase { Idle, Opening, Open, Reading, AddingListener, Closing, Uncertain }
    private inner class Reader:DataSource {
        private var phase=Phase.Idle
        private var source:DataSource?=null
        private var key:String?=null
        private var expected:PartitionReadRoute?=null
        private var lost=false
        private var construction:Any?=null
        private var uncertainty:Throwable?=null
        private fun retain(failure:Throwable) { if(uncertainty==null) uncertainty=failure; phase=Phase.Uncertain }
        private fun partial(failure:Throwable)=failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain
        private val listeners=arrayOfNulls<TransferListener>(4)
        private fun checked() {
            if(lost) throw IOException("Mixed saved reader lost its route; close before reopening")
            unchanged(requireNotNull(key),requireNotNull(expected))
        }
        @Synchronized override fun addTransferListener(listener:TransferListener) {
            check(phase==Phase.Idle || phase==Phase.Open) { "Saved reader operation active or uncertain" }
            if(listeners.any { it===listener }) return
            val slot=listeners.indexOfFirst { it==null }
            check(slot>=0) { "Saved reader listener budget is full" }
            listeners[slot]=listener
            if(phase==Phase.Open) {
                phase=Phase.AddingListener
                try { source!!.addTransferListener(listener) }
                catch(failure:Throwable) { if(partial(failure)) retain(failure); throw failure }
                finally { if(phase!=Phase.Uncertain) phase=Phase.Open }
            }
        }
        @Synchronized override fun open(spec:DataSpec):Long {
            check(phase==Phase.Idle) { "Saved reader already open or uncertain" }
            phase=Phase.Opening
            try {
                key=CacheKeyFactory.DEFAULT.buildCacheKey(spec)
                expected=route(key!!)
                checked()
                val selected=audio(expected!!); construction=selected
                val factory=selected.source; construction=factory
                val child=factory.createDataSource()
                source=child; construction=null
                for(listener in listeners) if(listener!=null) child.addTransferListener(listener)
                checked()
                val length=child.open(spec.buildUpon().setKey(key).build())
                checked(); phase=Phase.Open; return length
            } catch(failure:Throwable) {
                if(construction!=null) {
                    val unknown=PartitionOwnershipUncertain(this,failure); retain(unknown); throw unknown
                }
                if(partial(failure)) retain(failure)
                else try { finish() } catch(cleanup:Throwable) { if(cleanup!==failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        @Synchronized override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            check(phase==Phase.Open) { "Saved reader not open or busy" }
            phase=Phase.Reading
            try {
                checked()
                val count=source!!.read(buffer,offset,length)
                checked(); return count
            } catch(failure:Throwable) { lost=true; if(partial(failure)) retain(failure); throw failure }
            finally { if(phase!=Phase.Uncertain) phase=Phase.Open }
        }
        @Synchronized override fun getUri():Uri?=if(lost || phase==Phase.Idle || phase==Phase.Uncertain) null else source?.uri
        @Synchronized override fun getResponseHeaders():Map<String,List<String>> =
            if(lost || phase==Phase.Idle || phase==Phase.Uncertain) emptyMap() else source?.responseHeaders.orEmpty()
        @Synchronized override fun close() {
            if(phase==Phase.Idle) return
            if(phase==Phase.Uncertain) throw IOException("Saved reader close uncertain; child ownership retained")
            check(phase==Phase.Open) { "Saved reader operation already active" }
            finish()
        }
        private fun finish() {
            phase=Phase.Closing
            try { source?.close(); source=null; key=null; expected=null; lost=false; phase=Phase.Idle }
            catch(failure:Throwable) { retain(failure); throw PartitionOwnershipUncertain(this,failure) }
        }
    }
}
