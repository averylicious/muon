@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionSavedSourceTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=ArrayList<SimpleCache>()
    private val roots=HashMap<String,File>() // Small fixture only; no library map in production.
    private val bytes=byteArrayOf(3,6,9)
    private var opened=0
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun native(key:String):Cache {
        opened++
        val root=roots.getOrPut(key) { folders.newFolder() }
        val raw=SimpleCache(root,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
        if(raw.getCachedSpans(key).isEmpty()) {
            val hole=requireNotNull(raw.startReadWriteNonBlocking(key,0,bytes.size.toLong()))
            try { val file=raw.startFile(key,0,bytes.size.toLong()); file.writeBytes(bytes); raw.commitFile(file,bytes.size.toLong()) }
            finally { raw.releaseHoleSpan(hole) }
        }
        return PartitionResourceCache.open(raw,key)
    }
    private fun spec(key:String)=DataSpec.Builder().setUri(Uri.parse("fixture://saved/$key")).setKey(key).setLength(bytes.size.toLong()).build()
    private fun actual(cache:Cache)=CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()
    private fun read(source:DataSource) { val got=ByteArray(3); assertEquals(3,source.read(got,0,3)); assertArrayEquals(bytes,got) }

    @Test fun cachedFileReaderAndEvenEofKeepItsNativePartitionPinnedUntilCleanClose() {
        PartitionCacheLeases(::native,capacity=1).use { pool ->
            val factory=PartitionSavedSource(pool); val first=factory.createDataSource(); val other=factory.createDataSource()
            first.open(spec("first")); assertEquals(1,pool.active); assertEquals(1,pool.resident)
            assertThrows(PartitionCacheBusy::class.java) { other.open(spec("other")) }; assertEquals(1,opened)
            read(first); assertEquals(-1,first.read(ByteArray(1),0,1)); assertEquals(1,pool.active)
            assertThrows(PartitionCacheBusy::class.java) { other.open(spec("other")) }
            first.close(); first.close(); assertEquals(0,pool.active)
            other.open(spec("other")); assertEquals(2,opened); read(other); other.close()
            first.open(spec("first")); read(first); first.close(); assertEquals(3,opened)
            assertTrue(roots.values.all { root -> root.walkTopDown().any { it.name.endsWith(".exo") } })
        }
    }
    @Test fun samePartitionReadersShareOneNativeInstanceButEachOwnsABoundedPin() {
        PartitionCacheLeases(::native,capacity=1,leaseLimit=2).use { pool ->
            val factory=PartitionSavedSource(pool); val a=factory.createDataSource(); val b=factory.createDataSource(); val c=factory.createDataSource()
            a.open(spec("key")); b.open(spec("key")); assertEquals(1,opened); assertEquals(2,pool.active)
            assertThrows(PartitionCacheBusy::class.java) { c.open(spec("key")) }; assertEquals(2,pool.active)
            a.close(); c.open(spec("key")); read(b); read(c); b.close(); c.close(); assertEquals(0,pool.active)
        }
    }
    @Test fun stoppingPoolDoesNotCloseAReaderAndItsLastCleanCloseRetiresNativeStorage() {
        val pool=PartitionCacheLeases(::native,capacity=1); val source=PartitionSavedSource(pool).createDataSource()
        source.open(spec("key")); pool.close(); assertEquals(1,pool.resident); assertEquals(1,pool.active)
        read(source); assertThrows(IOException::class.java) { PartitionSavedSource(pool).createDataSource().open(spec("other")) }
        source.close(); assertEquals(0,pool.active); assertEquals(0,pool.resident)
        assertThrows(IllegalStateException::class.java) { caches.last().getCachedSpans("key") }
    }
    @Test fun missingSavedBytesFailWithoutNetworkOrSinkAndFailedOpenReleasesOnlyAfterCleanup() {
        val pool=PartitionCacheLeases({ key ->
            val raw=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
            PartitionResourceCache.open(raw,key)
        },capacity=1)
        pool.use {
            val source=PartitionSavedSource(pool).createDataSource()
            assertThrows(Exception::class.java) { source.open(spec("missing")) }; source.close()
            assertEquals(0,pool.active); assertTrue(caches.single().keys.isEmpty()); assertEquals(0,caches.single().cacheSpace)
            assertNull(source.uri); assertTrue(source.responseHeaders.isEmpty())
        }
    }
    @Test fun unknownSourceCloseRetainsItsPinAndRefusesRetryOrAnotherNativeInstance() {
        val pool=PartitionCacheLeases(::native,capacity=1)
        val factory=PartitionSavedSource(pool) { cache ->
            val inner=actual(cache)
            object:DataSource by inner {
                // Even an error after the native reader closes cannot prove that to the owner.
                override fun close() { inner.close(); throw IOException("Reader close outcome unknown") }
            }
        }
        val source=factory.createDataSource(); source.open(spec("key")); read(source)
        assertThrows(IOException::class.java) { source.close() }; assertEquals(1,pool.active)
        assertThrows(IOException::class.java) { source.close() }
        assertThrows(IllegalStateException::class.java) { source.open(spec("key")) }
        assertThrows(PartitionCacheBusy::class.java) { PartitionSavedSource(pool).createDataSource().open(spec("other")) }
        pool.close(); assertEquals(1,pool.active); assertEquals(1,pool.resident); assertEquals(1,opened)
    }
    @Test fun failedOpenWithUnknownCloseKeepsOriginalFailureAndBoundedUncertainPin() {
        val pool=PartitionCacheLeases(::native,capacity=1)
        val source=PartitionSavedSource(pool) { cache -> object:DataSource by actual(cache) {
            override fun open(dataSpec:DataSpec):Long { throw IOException("Original opening failure") }
            override fun close() { throw IOException("Unknown cleanup") }
        } }.createDataSource()
        val failure=assertThrows(IOException::class.java) { source.open(spec("key")) }
        assertEquals("Original opening failure",failure.message); assertEquals("Unknown cleanup",failure.suppressed.single().message)
        assertEquals(1,pool.active); pool.close(); assertEquals(1,pool.resident)
    }
    @Test fun cleanFailedOpenAndFailedSourceCreationBothReturnTheirPins() {
        PartitionCacheLeases(::native,capacity=1).use { pool ->
            var closes=0
            val source=PartitionSavedSource(pool) { cache -> object:DataSource by actual(cache) {
                override fun open(dataSpec:DataSpec):Long { throw IOException("Open failure") }
                override fun close() { closes++ }
            } }.createDataSource()
            assertThrows(IOException::class.java) { source.open(spec("key")) }; assertEquals(1,closes); source.close(); assertEquals(1,closes); assertEquals(0,pool.active)
            val bad=PartitionSavedSource(pool) { throw IOException("Creation failure") }.createDataSource()
            assertThrows(IOException::class.java) { bad.open(spec("key")) }; assertEquals(0,pool.active)
            val good=PartitionSavedSource(pool).createDataSource(); good.open(spec("key")); read(good); good.close()
        }
    }
    @Test fun readFailureKeepsTheCachedPartitionPinnedUntilCallerClosesIt() {
        PartitionCacheLeases(::native,capacity=1).use { pool ->
            val source=PartitionSavedSource(pool) { cache -> object:DataSource by actual(cache) {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int { throw IOException("Read interrupted") }
            } }.createDataSource()
            source.open(spec("key")); assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) }
            assertEquals(1,pool.active)
            assertThrows(PartitionCacheBusy::class.java) { PartitionSavedSource(pool).createDataSource().open(spec("other")) }
            source.close(); assertEquals(0,pool.active)
            val recovered=PartitionSavedSource(pool).createDataSource(); recovered.open(spec("key")); read(recovered); recovered.close()
        }
    }
    @Test fun repeatedThrowableDuringFailedOpenAndCleanupCannotMaskTheOriginalFailure() {
        val pool=PartitionCacheLeases(::native,capacity=1); val original=IOException("Same original failure")
        val source=PartitionSavedSource(pool) { cache -> object:DataSource by actual(cache) {
            override fun open(dataSpec:DataSpec):Long { throw original }
            override fun close() { throw original }
        } }.createDataSource()
        assertSame(original,assertThrows(IOException::class.java) { source.open(spec("key")) })
        assertEquals(1,pool.active); pool.close(); assertEquals(1,pool.resident)
    }
    @Test fun defaultKeyMatchesUriAndDuplicateOpenDoesNotLoseTheOriginalPin() {
        PartitionCacheLeases(::native,capacity=1).use { pool ->
            val source=PartitionSavedSource(pool).createDataSource(); val uri=Uri.parse("fixture://exact/key?query=1")
            val request=DataSpec.Builder().setUri(uri).setLength(3).build()
            source.open(request); assertTrue(roots.containsKey(uri.toString())); assertEquals(uri,source.uri)
            assertThrows(IllegalStateException::class.java) { source.open(request) }; assertEquals(1,pool.active)
            read(source); source.close(); assertNull(source.uri); assertTrue(source.responseHeaders.isEmpty())
        }
    }
    @Test fun listenersAreBoundedAndCallbacksCannotReenterReaderLifecycle() {
        PartitionCacheLeases(::native,capacity=1).use { pool ->
            val source=PartitionSavedSource(pool).createDataSource(); var initializations=0
            val listener=object:TransferListener {
                override fun onTransferInitializing(s:DataSource,spec:DataSpec,network:Boolean) {
                    initializations++
                    assertThrows(IllegalStateException::class.java) { source.open(spec) }
                    assertThrows(IllegalStateException::class.java) { source.close() }
                    assertThrows(IllegalStateException::class.java) { source.read(ByteArray(1),0,1) }
                }
                override fun onTransferStart(s:DataSource,spec:DataSpec,network:Boolean) {}
                override fun onBytesTransferred(s:DataSource,spec:DataSpec,network:Boolean,count:Int) {
                    assertThrows(IllegalStateException::class.java) { source.read(ByteArray(1),0,1) }
                }
                override fun onTransferEnd(s:DataSource,spec:DataSpec,network:Boolean) {
                    assertThrows(IllegalStateException::class.java) { source.close() }
                }
            }
            source.addTransferListener(listener); source.addTransferListener(listener)
            repeat(3) { source.addTransferListener(object:TransferListener by listener {}) }
            assertThrows(IllegalStateException::class.java) { source.addTransferListener(object:TransferListener by listener {}) }
            source.open(spec("key")); read(source); source.close(); assertEquals(4,initializations); assertEquals(0,pool.active)
        }
    }
}
