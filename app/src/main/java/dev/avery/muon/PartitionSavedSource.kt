@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import java.io.IOException

/** UNWIRED saved-copy reader. Default sources have NO upstream or sink: a missing byte fails, never
 * streams/re-downloads. A pool pin outlives cached-file reads, including EOF, and ends only after a
 * clean source close. The pool/factory owner must validate exact ready routing/volume/native identity.
 * This does not establish that owner or integrate legacy/download/played routing. */
internal class PartitionSavedSource(private val pool:PartitionCacheLeases,
    /** Internal fixture seam. The production default is the actual read-only Media3 cache source. */
    private val readers:(Cache)->DataSource={ cache -> CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource() }) : DataSource.Factory {
    override fun createDataSource():DataSource=Reader()
    private enum class Phase { Idle, Opening, Open, Reading, AddingListener, Closing, Uncertain }
    private inner class Reader:DataSource {
        private var phase=Phase.Idle
        private var pin:PartitionCacheLeases.Lease?=null
        private var source:DataSource?=null
        private val listeners=arrayOfNulls<TransferListener>(4)

        @Synchronized override fun addTransferListener(transferListener:TransferListener) {
            check(phase==Phase.Idle || phase==Phase.Open) { "Reader operation already active or uncertain" }
            if(listeners.any { it===transferListener }) return
            val slot=listeners.indexOfFirst { it==null }
            check(slot>=0) { "Partition reader listener budget is full" }
            listeners[slot]=transferListener
            if(phase==Phase.Open) {
                phase=Phase.AddingListener
                try { source!!.addTransferListener(transferListener) } finally { phase=Phase.Open }
            }
        }
        @Synchronized override fun open(dataSpec:DataSpec):Long {
            check(phase==Phase.Idle) { "Partition reader is already open or uncertain" }
            phase=Phase.Opening
            try {
                val key=CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
                pin=pool.acquire(key) // Before any source can observe a cached span/file.
                val opened=readers(pin!!.cache)
                source=opened
                for(listener in listeners) if(listener!=null) opened.addTransferListener(listener)
                val length=opened.open(dataSpec.buildUpon().setKey(key).build())
                phase=Phase.Open
                return length
            } catch(failure:Throwable) {
                // DataSource requires close even after failed open. Never drop a pin on unknown close.
                try { finish() } catch(closeFailure:Throwable) { if(closeFailure!==failure) failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
        @Synchronized override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            check(phase==Phase.Open) { "Partition reader is not open or is busy" }
            phase=Phase.Reading
            try { return source!!.read(buffer,offset,length) } finally { phase=Phase.Open }
        }
        @Synchronized override fun getUri():Uri?=if(phase==Phase.Idle || phase==Phase.Uncertain) null else source?.uri
        @Synchronized override fun getResponseHeaders():Map<String,List<String>> =
            if(phase==Phase.Idle || phase==Phase.Uncertain) emptyMap() else source?.responseHeaders.orEmpty()
        @Synchronized override fun close() {
            if(phase==Phase.Idle) return
            if(phase==Phase.Uncertain) throw IOException("Partition reader close is uncertain; its native pin is retained")
            check(phase==Phase.Open) { "Partition reader operation already active" }
            finish()
        }
        private fun finish() {
            phase=Phase.Closing
            try {
                source?.close()
                // Underlying readers/holes/listeners ended first. A stopped pool can now retire safely.
                pin?.close()
                source=null; pin=null; phase=Phase.Idle
            } catch(failure:Throwable) {
                // No queue or replacement instance can hide this uncertainty. Capacity stays counted,
                // and ordinary close retry cannot incorrectly release a possibly active cached reader.
                phase=Phase.Uncertain
                throw failure
            }
        }
    }
}
