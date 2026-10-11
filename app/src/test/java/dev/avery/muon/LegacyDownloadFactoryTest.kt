@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyDownloadFactoryTest {
    @get:Rule val folders=TemporaryFolder()
    private lateinit var database:StandaloneDatabaseProvider
    private lateinit var cache:SimpleCache
    private lateinit var barrier:SavedStorageBarrier
    private val payload=byteArrayOf(1,2,3,4,5)
    private val key="saved-lifetime-fixture"
    @Before fun before() {
        database=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { it.checkInitialization() }
        barrier=SavedStorageBarrier(1)
    }
    @After fun after() { try { cache.release() } finally { database.close() } }
    private fun bytes()=DataSource.Factory { ByteArrayDataSource(payload) }
    private fun request()=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:1/api1/fileopus/1")).setCustomCacheKey(key).build()
    private fun factory(upstream:DataSource.Factory=bytes())=LegacyDownloadFactory(cache,upstream,barrier)
    private fun held() { assertEquals(1,barrier.active); assertThrows(IOException::class.java) { barrier.exclusive() } }

    @Test fun actualDownloadAndCacheHitCloseUnderOnePermitAndPreserveAudio() {
        var closes=0
        val task=factory(DataSource.Factory {
            val raw=ByteArrayDataSource(payload)
            object:DataSource by raw { override fun close() { held(); closes++; raw.close() } }
        }).createDownloader(request())
        task.download { _,_,_->held() }
        assertEquals(1,closes); assertTrue(barrier.quiescent); assertTrue(cache.isCached(key,0,5))
        // CacheDataSource constructs its upstream eagerly, so construction remains allowed;
        // opening a fully cached copy must not contact it.
        val cached=LegacyDownloadFactory(cache,DataSource.Factory {
            val raw=ByteArrayDataSource(payload)
            object:DataSource by raw { override fun open(spec:DataSpec):Long { fail("Cache hit opened upstream"); return 0 } }
        },barrier).createDownloader(request())
        cached.download(null); assertTrue(barrier.quiescent)
        assertArrayEquals(payload,cache.getCachedSpans(key).first().file!!.readBytes())
    }

    @Test fun exclusiveAdmissionPreventsDownloadAndRemovalBeforeNativeMutation() {
        val exclusive=barrier.exclusive(); var created=0
        val task=factory(DataSource.Factory { created++; ByteArrayDataSource(payload) }).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }
        assertThrows(IOException::class.java) { task.remove() }
        assertEquals(0,created); assertTrue(cache.keys.isEmpty()); exclusive.close()
        task.download(null); assertTrue(cache.isCached(key,0,5)); task.remove()
        assertTrue(cache.keys.isEmpty()); assertTrue(barrier.quiescent)
    }

    @Test fun knownReadFailureClosesActualSourceThenAllowsRetry() {
        var reads=0; var closes=0
        val task=factory(DataSource.Factory {
            val raw=ByteArrayDataSource(payload)
            object:DataSource by raw {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    if(reads++==0) throw IOException("Known read failure")
                    return raw.read(buffer,offset,length)
                }
                override fun close() { held(); closes++; raw.close() }
            }
        }).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }; assertTrue(barrier.quiescent)
        task.download(null); assertTrue(cache.isCached(key,0,5)); assertEquals(2,closes)
        assertTrue(barrier.quiescent)
    }

    @Test fun swallowedUpstreamCloseFailureRetainsLeaseAndDisallowsRetryOrRemoval() {
        var closes=0; var quarantined=0
        val task=LegacyDownloadFactory(cache,DataSource.Factory {
            val raw=ByteArrayDataSource(payload)
            object:DataSource by raw { override fun close() { closes++; raw.close(); throw IOException("Unknown close") } }
        },barrier,onUncertain={quarantined++}).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }
        assertEquals(1,closes); assertEquals(1,quarantined); assertEquals(1,barrier.active)
        assertThrows(IOException::class.java) { task.download(null) }
        assertThrows(IOException::class.java) { task.remove() }
        assertThrows(IOException::class.java) { factory().createDownloader(request()).download(null) }
        assertTrue(cache.isCached(key,0,5)); assertEquals(1,closes)
    }

    @Test fun swallowedActualSinkCloseFailureRetainsCommittedBytesAndGate() {
        var closes=0
        val sink=DataSink.Factory {
            val raw=CacheDataSink.Factory().setCache(cache).createDataSink()
            object:DataSink by raw { override fun close() { closes++; held(); raw.close(); throw IOException("Unknown committed close") } }
        }
        val task=LegacyDownloadFactory(cache,bytes(),barrier,sinks=sink).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }
        assertEquals(1,closes); assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
        assertTrue(cache.isCached(key,0,5)); assertArrayEquals(payload,cache.getCachedSpans(key).first().file!!.readBytes())
    }

    @Test fun componentConstructionFailureRetainsExactFactoryAndRefusesReplacement() {
        var made=0
        val factory=DataSource.Factory { made++; throw IOException("Partially constructed owner") }
        val task=LegacyDownloadFactory(cache,factory,barrier).createDownloader(request())
        assertThrows(IOException::class.java) { task.download(null) }
        assertEquals(1,barrier.active); assertFalse(barrier.allowsShared)
        assertThrows(IOException::class.java) { task.download(null) }; assertEquals(1,made)
    }

    @Test fun cancelBeforeStartDoesNotConstructOrOpenSources() {
        var made=0
        val task=factory(DataSource.Factory { made++; ByteArrayDataSource(payload) }).createDownloader(request())
        task.cancel(); assertThrows(CancellationException::class.java) { task.download(null) }
        assertEquals(0,made); assertTrue(barrier.quiescent); assertTrue(cache.keys.isEmpty())
    }

    @Test fun cancellationWaitsForActualBlockedSourceCloseBeforeReleasingPermit() {
        val entered=CountDownLatch(1); var closes=0
        val task=factory(DataSource.Factory {
            val raw=ByteArrayDataSource(payload)
            object:DataSource by raw {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    held(); entered.countDown()
                    try { CountDownLatch(1).await(10,TimeUnit.SECONDS) } catch(_:InterruptedException) { throw InterruptedIOException("Cancelled fixture") }
                    throw IOException("Fixture timeout")
                }
                override fun close() { held(); closes++; raw.close() }
            }
        }).createDownloader(request())
        val worker=Executors.newSingleThreadExecutor()
        try {
            val done=worker.submit<Boolean> { try { task.download(null); false } catch(_:Exception) { true } }
            assertTrue(entered.await(5,TimeUnit.SECONDS)); held(); task.cancel()
            assertTrue(done.get(5,TimeUnit.SECONDS)); assertEquals(1,closes); assertTrue(barrier.quiescent)
        } finally { task.cancel(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS)) }
    }

    @Test fun byteRangeKeepsPinnedBoundedOpenFallbackBehavior() {
        var opens=0; var closes=0
        val task=factory(DataSource.Factory {
            val raw=ByteArrayDataSource(payload)
            object:DataSource by raw {
                override fun open(spec:DataSpec):Long { if(opens++==0) throw IOException("Initial bounded open"); return raw.open(spec) }
                override fun close() { closes++; raw.close() }
            }
        }).createDownloader(DownloadRequest.Builder("range",request().uri).setCustomCacheKey(key).setByteRange(0,5).build())
        task.download(null); assertEquals(2,opens); assertEquals(2,closes)
        assertTrue(cache.isCached(key,0,5)); assertTrue(barrier.quiescent)
    }

    @Test fun thrownOpaqueRemovalRetainsExactTaskAndRefusesNativeRetry() {
        factory().createDownloader(request()).download(null); var mutations=0; var notices=0
        val controlled=object:androidx.media3.datasource.cache.Cache by cache {
            override fun removeResource(key:String) { mutations++; held(); throw IOException("Unknown native removal") }
        }
        val task=LegacyDownloadFactory(controlled,bytes(),barrier,onUncertain={notices++}).createDownloader(request())
        assertThrows(IOException::class.java) { task.remove() }
        assertEquals(1,mutations); assertEquals(1,notices); assertEquals(1,barrier.active)
        assertThrows(IOException::class.java) { task.remove() }
        assertThrows(IOException::class.java) { task.download(null) }
        assertThrows(IOException::class.java) { factory().createDownloader(request()).remove() }
        assertEquals(1,mutations); assertTrue(cache.isCached(key,0,5)); assertFalse(barrier.allowsShared)
    }
    @Test fun knownSuccessfulNativeRemovalDrainsAndCanBeFollowedByAnotherSave() {
        val task=factory().createDownloader(request()); task.download(null)
        var mutations=0
        val observed=object:androidx.media3.datasource.cache.Cache by cache {
            override fun removeResource(key:String) { mutations++; held(); cache.removeResource(key) }
        }
        LegacyDownloadFactory(observed,bytes(),barrier).createDownloader(request()).remove()
        assertEquals(1,mutations); assertTrue(barrier.quiescent); assertFalse(cache.isCached(key,0,5))
        task.download(null); assertTrue(cache.isCached(key,0,5)); assertTrue(barrier.quiescent)
    }
    @Test fun failedAdmissionDoesNotPoisonAHealthyNativeRemovalTask() {
        var mutations=0
        val observed=object:androidx.media3.datasource.cache.Cache by cache {
            override fun removeResource(key:String) { mutations++; cache.removeResource(key) }
        }
        val task=LegacyDownloadFactory(observed,bytes(),barrier).createDownloader(request())
        val lock=barrier.exclusive()
        try { assertThrows(IOException::class.java) { task.remove() }; assertEquals(0,mutations) }
        finally { lock.close() }
        task.remove(); assertEquals(1,mutations); assertTrue(barrier.quiescent)
    }

    @Test fun nativeRemovalCallbackCannotAcknowledgeARowThatTheManagerFailedToDelete() {
        val index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,"removal_callback")
        val row=androidx.media3.exoplayer.offline.Download(request(),androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,1,2,5,0,0)
        index.putDownload(row); var confirmed=0; var unavailable=0
        OfflineStore.confirmRemovalCallback(index,key,barrier,{confirmed++},{unavailable++})
        assertEquals(0,confirmed); assertEquals(1,unavailable); assertNotNull(index.getDownload(key)); assertTrue(barrier.quiescent)
        index.removeDownload(key)
        OfflineStore.confirmRemovalCallback(index,key,barrier,{held(); confirmed++},{unavailable++})
        assertEquals(1,confirmed); assertEquals(1,unavailable); assertTrue(barrier.quiescent)
    }
    @Test fun pausedOrFailedIndexCallbackCannotEraseMarksOrAcknowledgeRemoval() {
        val index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,"failed_callback")
        var reads=0; var confirmed=0; var unavailable=0
        val controlled=object:androidx.media3.exoplayer.offline.DownloadIndex by index {
            override fun getDownload(id:String):androidx.media3.exoplayer.offline.Download? { reads++; throw IOException("Unknown native index result") }
        }
        val lock=barrier.exclusive()
        try { OfflineStore.confirmRemovalCallback(controlled,key,barrier,{confirmed++},{unavailable++}); assertEquals(0,reads) }
        finally { lock.close() }
        OfflineStore.confirmRemovalCallback(controlled,key,barrier,{confirmed++},{unavailable++})
        assertEquals(1,reads); assertEquals(0,confirmed); assertEquals(2,unavailable); assertEquals(1,barrier.active)
        assertFalse(barrier.allowsShared)
    }
}
