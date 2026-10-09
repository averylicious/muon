@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import java.io.IOException
import java.util.concurrent.CancellationException

/** UNWIRED supported progressive downloader adapter. The pool owns ready/allocated partition routing;
 * no legacy cache can masquerade as a bounded backend. A native downloader is created per operation
 * under a writer pin. Pinned ProgressiveDownloader waits for its worker, but CacheWriter swallows some
 * close failures: track actual file/upstream/sink closure before dropping that pin. An uncertain close
 * keeps all handles in a bounded quarantined pool slot and stops admission; never retry that instance.
 *
 * removeAdmitted MUST belong to the production destructive-command owner. Refusing in Downloader.remove
 * alone cannot preserve a DownloadManager row: command admission must happen BEFORE the manager sees
 * it (#230). This adapter is not that gate and is deliberately not wired to any production manager.
 */
internal class PartitionDownloadFactory(private val pool:PartitionCacheLeases,
    private val upstream:DataSource.Factory,
    private val removeAdmitted:(id:String,key:String,cache:Cache)->Unit,
    private val files:DataSource.Factory=FileDataSource.Factory(),
    /** Prepared new-save mode: full coverage + actual durable owner close before manager completion.
     * The production caller must separately own exact request/command/cover/completion admission. */
    private val sealNewSaves:Boolean=false,
    private val barrier:SavedStorageBarrier?=null,
    private val sinks:(Cache)->DataSink.Factory={CacheDataSink.Factory().setCache(it)}) : DownloaderFactory {
    override fun createDownloader(request:DownloadRequest):Downloader {
        val uri=request.uri; val key=request.customCacheKey ?: uri.toString()
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES || request.id.length.toLong()*2>MIGRATION_KEY_BYTES ||
            uri.toString().length.toLong()*2>MIGRATION_KEY_BYTES || (request.mimeType?.length ?: 0).toLong()*2>MIGRATION_KEY_BYTES)
            throw IOException("Partition download identity exceeds its budget")
        if(Util.inferContentTypeForUriAndMimeType(uri,request.mimeType)!=C.CONTENT_TYPE_OTHER || request.streamKeys.isNotEmpty() || request.timeRange!=null)
            throw IOException("Partition downloads support progressive audio only")
        if(sealNewSaves && (request.id!=key || !key.startsWith(NEW_SAVE_PREFIX) || request.byteRange!=null))
            throw IOException("Sealed saves require a fresh full-resource request/key")
        // Retain only bounded scalar identity/range, never request.data or a collection of stream keys.
        val range=request.byteRange
        return Task(request.id,key,uri,range?.offset ?: 0,range?.length ?: C.LENGTH_UNSET.toLong())
    }
    private class Broken(val downloader:Downloader?,val tracker:ClosureTracker?,val failure:Throwable)
    private inner class Task(private val id:String,private val key:String,private val uri:Uri,
        private val position:Long,private val length:Long):Downloader {
        private val lock=Any()
        private var running=false
        private var uncertain=false
        private var canceled=false
        private var started=false
        private var native:Downloader?=null
        override fun cancel() {
            val current=synchronized(lock) { canceled=true; if(started) native else null }
            current?.cancel()
        }
        override fun download(progressListener:Downloader.ProgressListener?)=operate(false) { cache,tracker ->
            synchronized(lock) { if(canceled) throw CancellationException("Download canceled") }
            val factory=CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { tracker.source(upstream) }
                .setCacheReadDataSourceFactory { tracker.source(files) }
                .setCacheWriteDataSinkFactory { tracker.sink(sinks(cache)) }
            // The supported default executor runs directly on DownloadManager's existing task thread.
            val downloader=ProgressiveDownloader(MediaItem.Builder().setUri(uri).setCustomCacheKey(key).build(),factory,position,length)
            synchronized(lock) { native=downloader; if(canceled) throw CancellationException("Download canceled") }
            downloader.download { content,bytes,percent ->
                val stop=synchronized(lock) { started=true; canceled }
                // The first progress call is inside an established native worker. Never cancel an
                // unstarted native downloader (its pinned finally path expects a worker to exist).
                if(stop) downloader.cancel() else progressListener?.onProgress(content,bytes,percent)
            }
            synchronized(lock) { if(canceled) throw CancellationException("Download canceled") }
        }
        override fun remove()=operate(true) { cache,_ -> removeAdmitted(id,key,cache) }
        private fun operate(exclusive:Boolean,work:(Cache,ClosureTracker)->Unit) {
            synchronized(lock) {
                if(running || uncertain) throw IOException("Partition download is busy or uncertain")
                if(!exclusive && canceled) throw CancellationException("Download canceled")
                running=true; started=false
            }
            var storage:SavedStorageBarrier.Lease?=null
            var lease:PartitionCacheLeases.Lease?=null
            var backend:PartitionOwnedCache?=null
            var tracker:ClosureTracker?=null
            var failure:Throwable?=null
            try {
                storage=if(exclusive) barrier?.exclusive() else barrier?.shared()
                storage?.check()
                lease=pool.acquireWriter(key,exclusive)
                backend=lease.cache as? PartitionOwnedCache ?: throw IOException("Downloader requires an owned partition")
                backend.checkQuiescent()
                if(sealNewSaves && !exclusive) backend.checkNewSave(key)
                tracker=ClosureTracker { storage?.check() }
                work(lease.cache,tracker)
                storage?.check()
                if(sealNewSaves && !exclusive) {
                    backend.checkNewSave(key)
                    if(savedAudioState(lease.cache,key).coverage!=SavedCoverage.Full)
                        throw IOException("Save cannot complete without full declared coverage")
                }
            } catch(caught:Throwable) {
                failure=caught
                if(caught is PartitionOwnershipUncertain) {
                    synchronized(lock) { uncertain=true }
                    lease?.quarantine(caught); storage?.quarantine(caught)
                }
            }
            finally {
                try {
                    if(lease!=null && !uncertain) {
                        if(backend!=null) {
                            if(tracker?.clean()==false) throw IOException("Partition source or sink close remains uncertain")
                            backend.checkQuiescent()
                        }
                        if(sealNewSaves && !exclusive && failure==null) {
                            if(!lease.tryRetireWriter()) {
                                // All I/O is known closed; contention is not unknown native closure.
                                // Keep the other reader and report failure instead of completing early.
                                lease.close(); failure=PartitionCacheBusy()
                            } else backend!!.checkNewSave(key,sealed=true)
                        } else lease.close()
                    }
                } catch(closeFailure:Throwable) {
                    synchronized(lock) { uncertain=true }
                    val retained=Broken(native,tracker,closeFailure)
                    lease?.quarantine(retained)
                    storage?.quarantine(retained)
                    if(failure==null) failure=closeFailure else if(closeFailure!==failure) failure!!.addSuppressed(closeFailure)
                } finally {
                    try { if(!uncertain) storage?.close() }
                    finally { synchronized(lock) { running=false; started=false; native=null } }
                }
            }
            failure?.let { throw it }
        }
    }
}

/** Exactly three components per native progressive downloader: upstream, file reader and cache sink.
 * Clean close after failed open is required too. A close failure is latched; a later retry cannot
 * turn it into evidence of closure. Controls never accumulate per hole/read/retry. */
private class ClosureTracker(private val checkpoint:()->Unit={}) {
    private class Control { var active=false; var uncertain=false; var closing=false }
    private val controls=arrayOfNulls<Control>(3)
    @Synchronized private fun register():Control {
        val slot=controls.indexOfFirst { it==null }
        if(slot<0) throw IOException("Partition source/sink component budget is full")
        return Control().also { controls[slot]=it }
    }
    @Synchronized fun clean()=controls.all { it==null || (!it.active && !it.uncertain && !it.closing) }
    @Synchronized private fun opening(c:Control) {
        if(c.active || c.uncertain || c.closing) throw IOException("Partition component is already open or uncertain")
        c.active=true
    }
    @Synchronized private fun live(c:Control) {
        if(!c.active || c.uncertain || c.closing) throw IOException("Partition component is not open")
    }
    private fun close(c:Control,action:()->Unit) {
        synchronized(this) {
            if(c.uncertain || c.closing) throw IOException("Partition component close remains uncertain")
            if(!c.active) return
            c.closing=true
        }
        try { action(); synchronized(this) { c.active=false; c.closing=false } }
        catch(failure:Throwable) { synchronized(this) { c.uncertain=true; c.closing=false }; throw failure }
    }
    fun source(factory:DataSource.Factory):DataSource {
        val c=register(); val source=factory.createDataSource()
        return object:DataSource {
            override fun addTransferListener(listener:TransferListener)=source.addTransferListener(listener)
            override fun open(spec:DataSpec):Long { checkpoint(); opening(c); return source.open(spec).also { checkpoint() } }
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int { checkpoint(); live(c); return source.read(buffer,offset,length).also { checkpoint() } }
            override fun getUri()=source.uri
            override fun getResponseHeaders()=source.responseHeaders
            override fun close()=close(c) { source.close() }
        }
    }
    fun sink(factory:DataSink.Factory):DataSink {
        val c=register(); val sink=factory.createDataSink()
        return object:DataSink {
            override fun open(spec:DataSpec) { checkpoint(); opening(c); sink.open(spec); checkpoint() }
            override fun write(buffer:ByteArray,offset:Int,length:Int) { checkpoint(); live(c); sink.write(buffer,offset,length); checkpoint() }
            override fun close()=close(c) { sink.close() }
        }
    }
}
