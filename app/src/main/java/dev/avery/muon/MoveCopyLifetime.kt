@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import java.io.IOException

/** One process-owned copy. CacheWriter quietly closes on failure, so inspect actual component
 * lifetime separately. Unknown raw closure retains this exact entry and stops further copies;
 * no reset/retry, staging deletion, source-removal or native-release authority. */
internal class MoveCopyLifetime {
    private class Entry(val output:MigrationIoOwnership) {
        var source:DataSource?=null
        var target:SavedFileReader?=null
        var writer:CacheDataSource?=null
        var sink:StrictMoveSink?=null
    }
    private var held:Entry?=null
    @Synchronized private fun acquire(outputs:MoveFileOutputs):Entry {
        if(held!=null) throw IOException("Move copy lifetime busy or uncertain")
        return Entry(MigrationIoOwnership(outputs)).also { held=it }
    }
    val retained:Boolean @Synchronized get()=held!=null
    fun copy(spec:DataSpec,source:DataSource.Factory,target:Cache,outputs:MoveFileOutputs,
        keepGoing:()->Boolean,unknownClose:()->Unit,files:DataSource.Factory=FileDataSource.Factory()) {
        val entry=acquire(outputs);var failure:Throwable?=null;var uncertain=false
        try {
            entry.source=source.createDataSource()
            entry.target=SavedFileReader(files.createDataSource())
            val sink=StrictMoveSink(target,MoveFileOutputs { entry.output.output(it) }) { entry.output.quiescent }
            entry.sink=sink
            val writer=CacheDataSource.Factory().setCache(target)
                .setCacheReadDataSourceFactory { requireNotNull(entry.target) }
                .setUpstreamDataSourceFactory { requireNotNull(entry.source) }
                .setCacheWriteDataSinkFactory { sink }.createDataSourceForDownloading()
            entry.writer=writer
            if(!keepGoing()) throw IOException("Move copy stopped")
            CacheWriter(writer,spec,null) { _,_,_->
                if(!keepGoing()) throw IOException("Move copy stopped")
            }.cache()
            sink.failure?.let { throw IOException("A moved file wasn't written out",it) }
            if(!sink.clean) throw IOException("A moved file wasn't fully written out")
        } catch(caught:Throwable) { failure=caught }
        finally {
            // The parent may have cleared currentDataSource after quiet close. Sticky real-file
            // latches and the output tracker still expose that failure without retrying raw close.
            val closes=listOf<()->Unit>(
                { entry.writer?.close() }, { entry.source?.close() }, { entry.target?.close() })
            for(close in closes) try { close() } catch(caught:Throwable) {
                uncertain=true
                if(failure==null) failure=caught else if(failure!==caught) failure!!.addSuppressed(caught)
            }
            if(!entry.output.quiescent) {
                uncertain=true
                if(failure==null) failure=IOException("Move output close uncertain; exact handle retained")
            }
            if(uncertain) {
                try { unknownClose() } catch(caught:Throwable) {
                    if(failure==null) failure=caught else if(failure!==caught) failure!!.addSuppressed(caught)
                }
            } else synchronized(this) { check(held===entry);held=null }
        }
        failure?.let { throw it }
    }
}
