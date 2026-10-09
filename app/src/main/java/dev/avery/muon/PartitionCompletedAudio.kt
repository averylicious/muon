@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.Download
import java.io.Closeable
import java.io.IOException

/** Fixed scalar snapshot of a bounded full COMPLETED manager row. Caller must bound index projection
 * before materializing a production request; validation here cannot undo an oversized index read.
 * A digest binds the exact local request, NOT remote audio authentication or migration verification. */
internal data class PartitionSaveCompletion private constructor(val key:String,val length:Long,
    val started:Long,val updated:Long,val requestDigest:String) {
    companion object {
        fun from(download:Download):PartitionSaveCompletion? {
            if(download.state!=Download.STATE_COMPLETED) return null
            val key=partitionSaveRequestKey(download.request)
            if(download.contentLength<=0 || download.stopReason!=Download.STOP_REASON_NONE ||
                download.failureReason!=Download.FAILURE_REASON_NONE)
                throw IOException("Completed save record has invalid completion state")
            val digest=partitionSaveRequestDigest(download.request).joinToString("") { "%02x".format(it) }
            return PartitionSaveCompletion(key,download.contentLength,download.startTimeMs,download.updateTimeMs,digest)
        }
    }
}

/** Prepared completed-new-save reader, not yet selected by app services. Closed ALONE is insufficient:
 * require an exact bounded COMPLETED index snapshot, matching UID/allocation and full native bytes.
 * Completed reads keep the journal Closed, use no upstream/sink and never fabricate migration Ready.
 * Restart can read a clean completed copy without restoring a process command token; Open/Opening/
 * Uncertain are not adopted. No replay, retry, cleanup or cover/record mutation is authorized.
 *
 * The mandatory lookup is the production owner's bounded index projection under the app-wide
 * writer/removal/eviction/availability barrier. Native owner and indexes are borrowed; close them
 * only after pool drain. These checks do not replace the still-required coordinator/barrier.
 */
internal class PartitionCompletedAudio(private val owner:PartitionNativeOwner,
    private val completion:(String)->PartitionSaveCompletion?,capacity:Int=PARTITION_NATIVE_INSTANCES):SavedAudio,Closeable {
    private data class Route(val saved:PartitionSaveRecord,val complete:PartitionSaveCompletion)
    @Volatile private var stopped=false
    internal val resident:Int get()=pool.resident
    internal val active:Int get()=pool.active
    private fun requireOpen() { if(stopped) throw IOException("Completed saved storage is stopped") }
    private fun route(key:String):Route? {
        val saved=owner.closedSaveRoute(key) ?: return null
        val complete=completion(key) ?: return null
        if(complete.key!=key) throw IOException("Completed save index identity changed")
        return Route(saved,complete)
    }
    private fun required(key:String):Route=route(key) ?: throw IOException("Save has no clean completed route")
    private fun unchanged(expected:Route) {
        if(route(expected.complete.key)!=expected) throw IOException("Completed save route or record changed")
    }
    private fun checked(cache:Cache,expected:Route):SavedAudioState {
        unchanged(expected)
        if(cache.uid!=expected.saved.uid) throw IOException("Completed save native identity changed")
        val state=savedAudioState(cache,expected.complete.key)
        if(state.coverage!=SavedCoverage.Full || state.bytes!=expected.complete.length ||
            ContentMetadata.getContentLength(state.metadata)!=expected.complete.length)
            throw IOException("Completed save does not have exact full native coverage")
        unchanged(expected); return state
    }
    private val pool=PartitionCacheLeases({ key ->
        val expected=required(key)
        val cache=owner.openClosedSaveRead(key)
        try { checked(cache,expected); cache }
        catch(failure:Throwable) {
            try { cache.release() } catch(cleanup:Throwable) { if(cleanup!==failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    },capacity=capacity)
    override val source:DataSource.Factory=PartitionSavedSource(pool,validation={ key ->
        requireOpen(); val expected=required(key)
        val check:()->Unit={ unchanged(expected) }
        check
    },cacheValidation={ key,cache -> checked(cache,required(key)); Unit })
    override fun inspect(key:String):SavedAudioState {
        requireOpen(); val expected=required(key)
        return pool.acquire(key).use { checked(it.cache,expected) }
    }
    /** Existence only; native UID/layout/coverage are checked by inspect/open, not by enumeration. */
    override fun contains(key:String):Boolean { requireOpen(); return route(key)!=null }
    override fun forEachKey(visit:(String)->Boolean) {
        var after:String?=null
        while(true) {
            requireOpen(); val page=owner.saveRoutePage(after)
            if(page.isEmpty()) return
            for(row in page) if(row.phase==PartitionSavePhase.Closed) {
                val key=row.ticket.allocation.key
                val expected=route(key) ?: continue
                val more=visit(key)
                requireOpen(); unchanged(expected)
                if(!more) return
            }
            after=page.last().ticket.allocation.key
        }
    }
    override fun close() { stopped=true; pool.close() }
}
