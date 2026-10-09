package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import java.io.IOException
import java.io.InterruptedIOException

internal class PlayedCopyTooLarge : IOException("Played song exceeds the selected cache limit")
internal class PlayedCopyCloseUncertain(val owner:PlayedCopyWriter,cause:Throwable):IOException(
    "Played-copy source closure is uncertain; cache cleanup was refused",cause)

/** Optional fresh copy, with one observable close attempt. Media3 CacheWriter quietly closes on
 * read/callback/open errors and can retry a bounded open; neither proves a failed child closed.
 * CacheDataSource retains its final upstream/read/write participants; retain this exact writer on
 * uncertainty. No resume/fallback, second close attempt, background retry or source cleanup. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayedCopyWriter(private val source:CacheDataSource,private val spec:DataSpec,
    private val limit:()->Long) {
    private val used=java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var cancelled=false
    fun cancel() { cancelled=true }
    private fun check() { if(cancelled) throw InterruptedIOException("Played copy cancelled") }
    fun cache() {
        if(!used.compareAndSet(false,true)) throw IOException("Played-copy writer already consumed; retry is unsupported")
        check() // No open/close if cancelled at installation.
        var attempted=false
        var failure:Throwable?=null
        try {
            attempted=true
            val length=source.open(spec)
            var copied=spec.position
            fun budget() {
                val maximum=limit().coerceAtLeast(0)
                if(copied>maximum || (length!=C.LENGTH_UNSET.toLong() &&
                    (length>maximum || spec.position>maximum-length))) throw PlayedCopyTooLarge()
            }
            budget()
            val buffer=ByteArray(CacheWriter.DEFAULT_BUFFER_SIZE_BYTES)
            while(true) {
                check()
                val count=source.read(buffer,0,buffer.size)
                if(count==C.RESULT_END_OF_INPUT) break
                if(count<=0) throw IOException("Played-copy source made no progress")
                copied=Math.addExact(copied,count.toLong()); budget()
            }
            check()
        } catch(error:Throwable) { failure=error; throw error }
        finally { if(attempted) try { source.close() } catch(close:Throwable) {
            val unknown=PlayedCopyCloseUncertain(this,close)
            if(failure!=null && failure!==close) unknown.addSuppressed(failure)
            throw unknown
        } }
    }
}

/** Blocking opportunistic copy only; explicit downloads must never use this policy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun copyPlayedWithinLimit(source:CacheDataSource,spec:DataSpec,
    onWriterCreated:(PlayedCopyWriter)->Unit={},limit:()->Long) {
    val key=requireNotNull(spec.key)
    require(key.startsWith(PLAYED_PREFIX)) { "Only played copies may be budget-limited" }
    try { PlayedCopyWriter(source,spec,limit).also(onWriterCreated).cache() }
    catch(failure:PlayedCopyTooLarge) {
        // Observable close succeeded; unknown close is a different typed failure and never deletes.
        source.cache.removeResource(key)
        throw failure
    }
}
