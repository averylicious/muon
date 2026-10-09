@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import java.io.IOException

/** One read-only cache source and one actual file component, both retained by the returned reader.
 * CacheDataSource clears its current source in finally after failed close; a later outer close can
 * therefore return normally. Latch the actual file failure across fragment changes/failed opens so
 * lifetime owners cannot mistake that later return for closure. No upstream/sink/cleanup retry.
 */
internal fun savedCacheReader(cache:Cache,files:DataSource.Factory?=null):DataSource {
    val raw=(files ?: FileDataSource.Factory()).createDataSource()
    var active=false; var closing=false; var uncertainty:Throwable?=null
    fun known() { uncertainty?.let { throw IOException("Saved file close uncertain; actual reader retained",it) } }
    val tracked=object:DataSource by raw {
        override fun open(spec:DataSpec):Long {
            known()
            if(active || closing) throw IOException("Saved file reader already active")
            active=true // A failed open still requires actual close.
            return raw.open(spec)
        }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int { known(); return raw.read(buffer,offset,length) }
        override fun close() {
            known()
            if(closing) {
                val refused=IOException("Saved file close already active")
                uncertainty=refused; throw refused
            }
            if(!active) return
            closing=true
            try { raw.close(); known(); active=false }
            catch(failure:Throwable) { if(uncertainty==null) uncertainty=failure; throw failure }
            finally { closing=false }
        }
    }
    val parent=CacheDataSource.Factory().setCache(cache).setCacheReadDataSourceFactory { tracked }
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()
    return object:DataSource by parent {
        override fun open(spec:DataSpec):Long { known(); return parent.open(spec).also { known() } }
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int { known(); return parent.read(buffer,offset,length).also { known() } }
        override fun close() {
            known(); parent.close(); known()
            if(active) {
                val refused=IOException("Saved file reader remains active after outer close")
                uncertainty=refused; throw refused
            }
        }
    }
}
