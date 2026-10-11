@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
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
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionDownloadFactoryTest {
    @get:Rule val folders=TemporaryFolder()
    private val db by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val key="saved/audio"
    private val payload=byteArrayOf(1,2,3,4,5)
    private val owned=ArrayList<Cache>()
    private lateinit var catalog:CachePartitionCatalog
    private lateinit var journal:CacheMigrationJournal
    private lateinit var owner:PartitionNativeOwner
    private lateinit var pool:PartitionCacheLeases
    private lateinit var budget:PartitionNativeBudget
    private lateinit var legacy:SimpleCache
    private fun prepare() {
        val root=folders.newFolder(); catalog=CachePartitionCatalog(root); journal=CacheMigrationJournal(root)
        budget=PartitionNativeBudget(1); owner=PartitionNativeOwner(catalog,journal,"internal",{"internal"},budget=budget)
        legacy=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),db); legacy.checkInitialization()
        legacy.applyContentMetadataMutations(key,ContentMetadataMutations().set("kept",byteArrayOf(8)))
        CacheMigrationPublication(catalog,journal,owner.migrationTarget(key)).migrate(legacy,key,{})
        pool=PartitionCacheLeases({ owner.openReady(it).also { cache -> owned+=cache } },capacity=1)
    }
    @After fun close() {
        if(::pool.isInitialized) pool.close()
        // Injected unknown-close fixtures close their actual disposable underlying handle first.
        // Only test teardown bypasses their retained synthetic pin; production never does this.
        owned.asReversed().forEach { it.release() }
        if(::legacy.isInitialized) legacy.release()
        if(::journal.isInitialized) journal.close()
        if(::catalog.isInitialized) catalog.close()
        db.close()
    }
    private fun request(builder:(DownloadRequest.Builder)->DownloadRequest.Builder={it})=
        builder(DownloadRequest.Builder("request/audio",Uri.parse("https://unused.invalid/audio.flac")).setCustomCacheKey(key)).build()
    private fun bytes()=DataSource.Factory { ByteArrayDataSource(payload) }
    private fun factory(upstream:DataSource.Factory=bytes(),remove:(String,String,Cache)->Unit={_,name,cache -> cache.removeResource(name)})=
        PartitionDownloadFactory(pool,upstream,remove)
    private fun cached():Long=pool.acquire(key).use { it.cache.cacheSpace }
    @Test fun actualDownloaderSharesBarrierAndRemovalRequiresGlobalExclusion() {
        prepare(); val barrier=SavedStorageBarrier(); var progress=0; var removes=0
        val factory=PartitionDownloadFactory(pool,bytes(),{_,name,cache -> removes++; cache.removeResource(name) },barrier=barrier)
        val task=factory.createDownloader(request())
        task.download { _,_,_ ->
            progress++; assertEquals(1,barrier.active)
            assertThrows(IOException::class.java) { barrier.exclusive() }
        }
        assertTrue(progress>0); assertTrue(barrier.quiescent); assertEquals(5L,cached())
        val held=barrier.shared()
        assertThrows(IOException::class.java) { task.remove() }; assertEquals(0,removes)
        held.close(); task.remove(); assertEquals(1,removes); assertTrue(barrier.quiescent)
    }
    @Test fun actualDownloaderUnknownCloseQuarantinesBothStorageAndNativePins() {
        prepare(); val barrier=SavedStorageBarrier()
        val upstream=DataSource.Factory {
            val actual=ByteArrayDataSource(payload)
            object:DataSource by actual { override fun close() { actual.close(); throw IOException("Injected unknown close") } }
        }
        val task=PartitionDownloadFactory(pool,upstream,{_,_,_ -> fail("No removal") },barrier=barrier).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }
        assertEquals(1,barrier.active); assertEquals(1,pool.active)
        assertThrows(IOException::class.java) { barrier.exclusive() }
    }
    @Test fun actualProgressiveDownloadWritesThroughOwnedSidecarAndReturnsOnlyItsWriterPin() {
        prepare(); var progress=0
        val task=factory().createDownloader(request())
        task.download { _,_,_ -> progress++ }
        assertTrue(progress>0); assertEquals(0,pool.active); assertEquals(5L,cached())
        pool.close(); assertEquals(0,budget.resident)
        val reopened=owner.openReady(key).also { owned+=it }
        assertEquals(5L,reopened.cacheSpace)
        assertArrayEquals(byteArrayOf(8),reopened.getContentMetadata(key).get("kept",null as ByteArray?))
        assertEquals(5L,androidx.media3.datasource.cache.ContentMetadata.getContentLength(reopened.getContentMetadata(key)))
        reopened.release(); assertEquals(0,budget.resident)
    }
    @Test fun sealedNewSaveModeCannotWriteOrCompleteAnExistingMigrationReadyCache() {
        prepare(); var upstreamOpens=0
        val sealed=PartitionDownloadFactory(pool,DataSource.Factory { upstreamOpens++; ByteArrayDataSource(payload) },
            {_,_,_ -> fail("No removal") },sealNewSaves=true)
        val freshLooking=DownloadRequest.Builder(key,request().uri).setCustomCacheKey(key).build()
        assertThrows(IOException::class.java) { sealed.createDownloader(freshLooking).download(null) }
        assertEquals(0,upstreamOpens); assertEquals(0,pool.active); assertEquals(0L,cached())
        assertNotNull(journal.ready(key))
        assertArrayEquals(byteArrayOf(8),legacy.getContentMetadata(key).get("kept",null as ByteArray?))
    }
    @Test fun completedCacheDoesNotNeedAnotherUpstreamOpenAndFullRequestDataIsNotRetained() {
        prepare(); val calls=AtomicInteger()
        val upstream=DataSource.Factory { calls.incrementAndGet(); ByteArrayDataSource(payload) }
        val task=factory(upstream).createDownloader(request { it.setData(ByteArray(1024*1024)) })
        task.download(null); assertEquals(1,calls.get()); assertEquals(0,pool.active)
        // Creating the native datasource is cheap; an already complete copy must not open upstream.
        val neverOpen=DataSource.Factory { object:DataSource by ByteArrayDataSource(payload) {
            override fun open(spec:DataSpec):Long { fail("Completed download opened upstream"); return 0 }
        } }
        factory(neverOpen).createDownloader(request()).download(null)
        assertEquals(5L,cached()); assertEquals(0,pool.active)
    }
    @Test fun ordinaryReadFailureCleanlyClosesItsUsesAndSameTaskCanResumeWithoutLosingOriginalMetadata() {
        prepare(); val once=AtomicBoolean(true)
        val upstream=DataSource.Factory {
            val actual=ByteArrayDataSource(payload); val fail=once.getAndSet(false); var reads=0
            object:DataSource by actual {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(fail && reads++>0) throw IOException("Transient read failure")
                    return actual.read(buffer,offset,if(fail) minOf(1,length) else length)
                }
            }
        }
        val task=factory(upstream).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }; assertEquals(0,pool.active)
        task.download(null); assertEquals(5L,cached()); assertEquals(0,pool.active)
        assertArrayEquals(byteArrayOf(8),legacy.getContentMetadata(key).get("kept",null as ByteArray?))
    }
    @Test fun failedOpenStillRequiresCleanCloseAndNativeBoundedRetryCanThenSucceed() {
        prepare(); val opens=AtomicInteger(); val closes=AtomicInteger()
        val upstream=DataSource.Factory {
            val actual=ByteArrayDataSource(payload)
            object:DataSource by actual {
                override fun open(spec:DataSpec):Long { if(opens.incrementAndGet()==1) throw IOException("First open failed"); return actual.open(spec) }
                override fun close() { closes.incrementAndGet(); actual.close() }
            }
        }
        // A byte range causes Media3's bounded-open then unbounded-fallback path.
        factory(upstream).createDownloader(request { it.setByteRange(0,5) }).download(null)
        assertEquals(2,opens.get()); assertEquals(2,closes.get()); assertEquals(5L,cached()); assertEquals(0,pool.active)
    }
    @Test fun swallowedUpstreamCloseFailureQuarantinesThePinAndStopsEveryNewPoolAdmission() {
        prepare(); val upstream=DataSource.Factory {
            val actual=ByteArrayDataSource(payload)
            object:DataSource by actual { override fun close() { actual.close(); throw IOException("Unknown upstream close") } }
        }
        val task=factory(upstream).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }
        assertEquals(1,pool.active); assertEquals(1,pool.resident); assertEquals(1,budget.resident)
        assertThrows(IOException::class.java) { task.download(null) }
        assertThrows(IOException::class.java) { pool.acquire(key) }
        pool.close(); assertEquals(1,budget.resident)
        assertArrayEquals(byteArrayOf(8),legacy.getContentMetadata(key).get("kept",null as ByteArray?))
    }
    @Test fun swallowedSinkCloseFailureIsNotSuccessEvenWhenItsCommitAlreadyWroteBytes() {
        prepare()
        val factory=PartitionDownloadFactory(pool,bytes(),{_,_,_ -> fail("No removal") },sinks={ cache ->
            DataSink.Factory {
                val actual=CacheDataSink.Factory().setCache(cache).createDataSink()
                object:DataSink by actual { override fun close() { actual.close(); throw IOException("Unknown sink close") } }
            }
        })
        assertThrows(IOException::class.java) { factory.createDownloader(request()).download(null) }
        assertEquals(1,pool.active); assertEquals(1,budget.resident)
        assertThrows(IOException::class.java) { pool.acquire(key) }
        assertArrayEquals(byteArrayOf(8),legacy.getContentMetadata(key).get("kept",null as ByteArray?))
    }
    @Test fun cancelBeforeStartOpensNothingAndIsPermanent() {
        prepare(); val created=AtomicInteger()
        val task=factory(DataSource.Factory { created.incrementAndGet(); ByteArrayDataSource(payload) }).createDownloader(request())
        task.cancel(); assertThrows(CancellationException::class.java) { task.download(null) }
        assertEquals(0,created.get()); assertEquals(0,pool.resident); assertEquals(0,budget.resident)
    }
    @Test fun cancelBlockedNativeReadWaitsForCleanCloseBeforeReturningItsPin() {
        prepare(); val entered=CountDownLatch(1); val closed=AtomicInteger()
        val upstream=DataSource.Factory {
            val actual=ByteArrayDataSource(payload)
            object:DataSource by actual {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    entered.countDown()
                    try { CountDownLatch(1).await(10,TimeUnit.SECONDS) } catch(_:InterruptedException) { throw InterruptedIOException("Canceled read") }
                    throw IOException("Test read deadline")
                }
                override fun close() { actual.close(); closed.incrementAndGet() }
            }
        }
        val task=factory(upstream).createDownloader(request()); val worker=Executors.newSingleThreadExecutor()
        try {
            val future=worker.submit<Boolean> { try { task.download(null); false } catch(_:Exception) { true } }
            assertTrue(entered.await(5,TimeUnit.SECONDS)); assertEquals(1,pool.active)
            task.cancel(); assertTrue(future.get(5,TimeUnit.SECONDS)); assertTrue(closed.get()>0)
            assertEquals(0,pool.active); pool.close(); assertEquals(0,budget.resident)
            assertThrows(CancellationException::class.java) { task.download(null) }
        } finally { task.cancel(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    }
    @Test fun removalNeedsAnExclusivePinAndCallsOnlyTheExplicitAdmittedAction() {
        prepare(); factory().createDownloader(request()).download(null); val reader=pool.acquire(key); var removed=0
        val remover=factory(remove={ id,name,cache -> assertEquals("request/audio",id); assertEquals(key,name); removed++; cache.removeResource(name) }).createDownloader(request())
        assertThrows(PartitionCacheBusy::class.java) { remover.remove() }; assertEquals(0,removed); assertEquals(5L,reader.cache.cacheSpace)
        reader.close(); remover.remove(); assertEquals(1,removed); assertEquals(0L,cached()); assertEquals(0,pool.active)
    }
    @Test fun refusedRemovalAndUnsupportedOrOversizedRequestsDoNotDeleteOrStartNativeWork() {
        prepare(); val deny=factory(remove={_,_,_ -> throw IOException("Command not admitted") })
        val unsupported=DownloadRequest.Builder("request",Uri.parse("https://unused.invalid/list.m3u8")).build()
        assertThrows(IOException::class.java) { deny.createDownloader(unsupported) }
        assertThrows(IOException::class.java) { deny.createDownloader(request { it.setCustomCacheKey("x".repeat(MIGRATION_KEY_BYTES)) }) }
        assertEquals(0,pool.resident); assertEquals(0,budget.resident)
        factory().createDownloader(request()).download(null)
        assertThrows(IOException::class.java) { deny.createDownloader(request()).remove() }
        assertEquals(5L,cached()); assertEquals(0,pool.active)
    }
    @Test fun stoppedPoolNativeReleaseFailureKeepsItsOriginalErrorAndIsNeverRetried() {
        prepare(); var releases=0
        pool=PartitionCacheLeases({ name ->
            val actual=owner.openReady(name).also { owned+=it }
            object:Cache by actual,PartitionOwnedCache {
                override fun checkQuiescent()=(actual as PartitionOwnedCache).checkQuiescent()
                override fun release() { releases++; actual.release(); throw IOException("Native release receipt unknown") }
            }
        },capacity=1)
        val task=factory().createDownloader(request())
        val failure=assertThrows(IOException::class.java) { task.download { _,_,_ -> pool.close() } }
        // The pin already ended before native release. Quarantine must not replace this exception
        // with an ended-pin assertion or retry a possibly completed native release.
        assertEquals("Native release receipt unknown",failure.message); assertEquals(1,releases)
        assertEquals(0,pool.active); assertEquals(1,pool.resident); assertEquals(0,budget.resident)
        assertThrows(IOException::class.java) { pool.acquire(key) }
        pool.close(); assertEquals(1,releases)
    }

}
