@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionAudioRemovalTest {
    @get:Rule val folders=TemporaryFolder()
    private fun <T> worker(work:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {work()}.get(10,TimeUnit.SECONDS)}
            catch(error:java.util.concurrent.ExecutionException) {throw requireNotNull(error.cause)} }
        finally {executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun write(cache:Cache,position:Long):File {
        val hole=cache.startReadWrite("saved/test",position,2)!!
        try {return cache.startFile("saved/test",position,2).also {it.writeBytes(byteArrayOf(2,4)); cache.commitFile(it,2)}}
        finally {cache.releaseHoleSpan(hole)}
    }
    @Test fun actualNativeSilentUnlinkFailureIsNotConfirmedEvenAfterItsSpanDisappears() {
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val actual=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),provider)
        try {worker {
            actual.checkInitialization(); val file=write(actual,0); val span=actual.getCachedSpans("saved/test").single()
            // The actual Media3 removeSpan runs, with its File.delete outcome deterministically false.
            // No OS permissions/root assumptions or replacement native cache/index implementation.
            val failed=CacheSpan(span.key,span.position,span.length,span.lastTouchTimestamp,
                object:File(file.path) {override fun delete()=false})
            val observed=object:Cache by actual {
                override fun getCachedSpans(key:String)=TreeSet<CacheSpan>().apply {add(failed)}
                override fun removeSpan(span:CacheSpan)=actual.removeSpan(span)
            }
            assertThrows(IOException::class.java) {removePartitionAudioConfirmed(observed,"saved/test") {}}
            assertTrue(actual.getCachedSpans("saved/test").isEmpty()); assertTrue(file.isFile)
            assertArrayEquals(byteArrayOf(2,4),file.readBytes())
        }} finally {actual.release(); provider.close()}
    }
    @Test fun actualHealthyRemovalConfirmsEveryFileAbsentAndDirectorySyncBeforeReturning() {
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val actual=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),provider)
        try {worker {
            actual.checkInitialization(); val first=write(actual,0); val second=write(actual,2)
            removePartitionAudioConfirmed(actual,"saved/test") {}
            assertFalse(first.exists()); assertFalse(second.exists()); assertTrue(actual.getCachedSpans("saved/test").isEmpty())
        }} finally {actual.release(); provider.close()}
    }
    @Test fun cancellationAfterOneActualUnlinkPropagatesAndDoesNotPretendTheRemainingFileWasRemoved() {
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val actual=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),provider)
        try {worker {
            actual.checkInitialization(); val first=write(actual,0); val second=write(actual,2)
            assertThrows(java.io.InterruptedIOException::class.java) {removePartitionAudioConfirmed(actual,"saved/test") {
                if(!first.exists()) throw java.io.InterruptedIOException("Cancel after actual first unlink")
            }}
            assertFalse(first.exists()); assertTrue(second.exists()); assertEquals(1,actual.getCachedSpans("saved/test").size)
        }} finally {actual.release(); provider.close()}
    }
}
