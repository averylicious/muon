@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.IOException
import java.util.concurrent.CancellationException

/** Prepared actual-manager adapter. Validation happens inside the download task: throwing from
 * createDownloader can kill Media3's internal handler rather than retain a FAILED row. This proxy
 * holds the already-budgeted manager request, not a copied payload or unbounded separate work queue.
 * Removal still MUST be refused before manager delivery: throwing here alone cannot preserve its row.
 */
internal class PartitionCommandDownloader(private val commands:PartitionSaveCommands,
    pool:PartitionCacheLeases,upstream:DataSource.Factory,barrier:SavedStorageBarrier?=null) : DownloaderFactory {
    private val factory=PartitionDownloadFactory(pool,upstream,
        {_,_,_->throw IOException("Partition removal requires production pre-manager admission")},sealNewSaves=true,barrier=barrier)
    override fun createDownloader(request:DownloadRequest):Downloader = object:Downloader {
        private var delegate:Downloader?=null
        private var canceled=false
        override fun download(progress:Downloader.ProgressListener?) {
            val task=synchronized(this) {
                if(canceled) throw CancellationException("Save command canceled")
                commands.download(request)
                delegate ?: factory.createDownloader(request).also { delegate=it }
            }
            task.download(progress)
        }
        override fun cancel() {
            val task=synchronized(this) { canceled=true; delegate }
            task?.cancel()
        }
        override fun remove() { throw IOException("Partition removal requires pre-manager admission") }
    }
}
