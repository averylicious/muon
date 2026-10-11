@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import java.io.IOException
import java.util.concurrent.CancellationException

/** Actual legacy DownloadManager task lifetime, not command submission or a completion callback.
 * Native CacheWriter quietly closes on some error paths; observe all three real participants and
 * retain the exact task/tracker/permit if any close or component construction is uncertain. This
 * exclusion prepares migration; it does not authorize destructive commands before manager admission.
 */
internal class LegacyDownloadFactory(private val cache:Cache,private val upstream:DataSource.Factory,
    private val barrier:SavedStorageBarrier,private val onUncertain:()->Unit={},private val files:DataSource.Factory=FileDataSource.Factory(),
    private val sinks:DataSink.Factory=CacheDataSink.Factory().setCache(cache)):DownloaderFactory {
    override fun createDownloader(request:DownloadRequest):Downloader {
        val key=request.customCacheKey ?: request.uri.toString()
        if(key.length.toLong()*2>MIGRATION_KEY_BYTES || request.uri.toString().length.toLong()*2>MIGRATION_KEY_BYTES ||
            (request.mimeType?.length ?: 0).toLong()*2>MIGRATION_KEY_BYTES)
            throw IOException("Saved download identity exceeds its budget")
        if(Util.inferContentTypeForUriAndMimeType(request.uri,request.mimeType)!=C.CONTENT_TYPE_OTHER)
            throw IOException("Saved downloads support progressive audio only")
        val range=request.byteRange
        // No request.data/stream-key list retention. Normal app requests are progressive full audio;
        // native byte-range requests retain their existing offset/length behavior.
        return Task(request.uri,key,range?.offset ?: 0,range?.length ?: C.LENGTH_UNSET.toLong())
    }
    private class Broken(val task:Any,val native:Downloader?,val tracker:ClosureTracker,val failure:Throwable?)
    private inner class Task(private val uri:android.net.Uri,private val key:String,
        private val position:Long,private val length:Long):Downloader {
        private val lock=Any()
        private var running=false
        private var uncertain=false
        private var cancelled=false
        private var started=false
        private var native:Downloader?=null
        override fun cancel() {
            val task=synchronized(lock) { cancelled=true; if(started) native else null }
            task?.cancel()
        }
        override fun download(progress:Downloader.ProgressListener?)=operate { tracker ->
            synchronized(lock) { if(cancelled) throw CancellationException("Saved download cancelled") }
            val factory=CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory { tracker.source(upstream) }
                .setCacheReadDataSourceFactory { tracker.source(files) }
                .setCacheWriteDataSinkFactory { tracker.sink(sinks) }
            // Direct executor: Media3's existing manager task thread owns all actual I/O. No separate
            // submitted runnable can outlive this wrapper or require ambiguous executor admission.
            val downloader=ProgressiveDownloader(MediaItem.Builder().setUri(uri).setCustomCacheKey(key).build(),factory,position,length)
            synchronized(lock) { native=downloader; if(cancelled) throw CancellationException("Saved download cancelled") }
            downloader.download { content,bytes,percent ->
                val stop=synchronized(lock) { started=true; cancelled }
                // Pinned native finally assumes a worker exists; cancel only after first progress.
                if(stop) downloader.cancel() else progress?.onProgress(content,bytes,percent)
            }
            synchronized(lock) { if(cancelled) throw CancellationException("Saved download cancelled") }
        }
        override fun remove()=operate(opaqueMutation=true) { cache.removeResource(key) }
        private fun operate(opaqueMutation:Boolean=false,work:(ClosureTracker)->Unit) {
            synchronized(lock) {
                if(running || uncertain) throw IOException("Saved download is busy or uncertain")
                running=true; started=false
            }
            var lease:SavedStorageBarrier.Lease?=null
            val tracker=ClosureTracker { lease?.check() }
            var failure:Throwable?=null
            var entered=false
            var returned=false
            try {
                lease=barrier.shared(); lease.check()
                entered=true
                work(tracker); returned=true; lease.check()
            } catch(caught:Throwable) { failure=caught }
            finally {
                try {
                    if(!tracker.clean() || (opaqueMutation && entered && !returned)) {
                        // A removal has no source/sink tracker to expose its native partial result.
                        // A throwing opaque mutation must not masquerade as known quiescence.
                        val broken=Broken(this,native,tracker,failure)
                        synchronized(lock) { uncertain=true }
                        lease?.quarantine(broken)
                        try { onUncertain() } catch(callback:Throwable) {
                            if(failure==null) failure=callback else failure!!.addSuppressed(callback)
                        }
                        val unknown=IOException("Saved download component construction, close or mutation is uncertain")
                        if(failure==null) failure=unknown else failure!!.addSuppressed(unknown)
                    } else lease?.close()
                } finally { synchronized(lock) { running=false; started=false; native=null } }
            }
            failure?.let { throw it }
        }
    }
}
