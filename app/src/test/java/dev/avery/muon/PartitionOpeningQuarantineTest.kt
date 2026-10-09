@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadRequest
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
class PartitionOpeningQuarantineTest {
    @get:Rule val folders=TemporaryFolder()
    private val db by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val key="original/audio"
    private val payload=byteArrayOf(3,1,4)
    private val fixtures=ArrayList<Fixture>()
    private inner class Fixture {
        val root=folders.newFolder()
        val catalog=CachePartitionCatalog(root)
        val journal=CacheMigrationJournal(root)
        val budget=PartitionNativeBudget(1)
        var failAfterNative=false
        var unavailable=false
        var bytes:File?=null
        val owner=PartitionNativeOwner(catalog,journal,"internal",{
            if(unavailable || (failAfterNative && bytes?.let(SimpleCache::isCacheFolderLocked)==true)) null else "internal"
        },budget=budget)
        val legacy=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),db).also { it.checkInitialization() }
        val pool=PartitionCacheLeases(owner::openReady,capacity=1)
        val barrier=SavedStorageBarrier()
        var actual:Cache?=null
        init {
            val hole=requireNotNull(legacy.startReadWriteNonBlocking(key,0,3))
            try { val file=legacy.startFile(key,0,3); file.writeBytes(payload); legacy.commitFile(file,3) }
            finally { legacy.releaseHoleSpan(hole) }
            val ready=CacheMigrationPublication(catalog,journal,owner.migrationTarget(key)).migrate(legacy,key,{})
            bytes=File(catalog.directory(ready.ticket.allocation),"bytes")
        }
        fun unknown() {
            assertEquals(1,budget.resident); assertEquals(1,pool.resident)
            assertEquals(1,barrier.active); assertFalse(barrier.quiescent)
            assertTrue(SimpleCache.isCacheFolderLocked(requireNotNull(bytes)))
            assertThrows(IOException::class.java) { barrier.exclusive() }
            assertThrows(IOException::class.java) { barrier.shared() }
            assertThrows(IOException::class.java) { pool.acquire(key) }
            assertThrows(PartitionOwnershipUncertain::class.java) { owner.openReady(key) }
            assertArrayEquals(payload,requireNotNull(legacy.getCachedSpans(key).single().file).readBytes())
        }
        fun audio()=BarrierSavedAudio(object:SavedAudio {
            override val source=PartitionSavedSource(pool)
            override fun contains(key:String)=pool.acquire(key).use { true }
            override fun inspect(key:String):SavedAudioState=throw UnsupportedOperationException()
            override fun forEachKey(visit:(String)->Boolean) { contains(key); visit(key) }
        },barrier)
        fun closeFixture() {
            // TEST ONLY: after all assertions close the exact retained actual handles. No production
            // retry/reset or cleanup is exposed by this reflection teardown.
            val unknown=owner.javaClass.getDeclaredField("uncertainty").apply { isAccessible=true }.get(owner) as PartitionOwnershipUncertain?
            if(unknown!=null) {
                val handles=unknown.owner
                for(name in listOf("native","metadata","database")) {
                    val value=handles.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(handles)
                    when(value) { is SimpleCache -> value.release(); is PartitionContentMetadata -> value.close(); is SQLiteDatabase -> value.close() }
                }
            } else { pool.close(); actual?.release() }
            legacy.release(); journal.close(); catalog.close()
        }
    }
    private fun fixture()=Fixture().also { fixtures+=it }
    @After fun close() { fixtures.asReversed().forEach { it.closeFixture() }; db.close() }
    private fun spec()=DataSpec.Builder().setUri(Uri.parse("muon-saved:original")).setKey(key).setLength(3).build()
    @Test fun realNativeCreationFailureBeforeReturningLeaseRetainsDownloaderAndGlobalAdmission() {
        val f=fixture(); f.failAfterNative=true; var upstream=0
        val factory=PartitionDownloadFactory(f.pool,DataSource.Factory { upstream++; ByteArrayDataSource(payload) },
            {_,_,_ -> fail("No removal") },barrier=f.barrier)
        val task=factory.createDownloader(DownloadRequest.Builder("request",Uri.parse("https://unused.invalid/audio.flac")).setCustomCacheKey(key).build())
        assertThrows(PartitionOwnershipUncertain::class.java) { task.download(null) }
        assertEquals(0,upstream); f.failAfterNative=false; f.unknown()
        assertThrows(IOException::class.java) { task.download(null) }; f.unknown()
    }
    @Test fun savedReaderFailedNativeOpenCannotReleaseOuterGateByClosingEmptyChild() {
        val f=fixture(); f.failAfterNative=true; val reader=f.audio().source.createDataSource()
        assertThrows(PartitionOwnershipUncertain::class.java) { reader.open(spec()) }
        f.failAfterNative=false; f.unknown()
        assertThrows(IOException::class.java) { reader.close() }; f.unknown()
    }
    @Test fun scalarSavedObservationRetainsGateWhenNativeFactoryNeverReturns() {
        val f=fixture(); f.failAfterNative=true
        assertThrows(PartitionOwnershipUncertain::class.java) { f.audio().contains(key) }
        f.failAfterNative=false; f.unknown()
    }
    @Test fun realUnknownIdleReleaseDuringReplacementRetainsNativeAndStorageGate() {
        val f=fixture(); f.pool.acquire(key).use { f.actual=it.cache }
        f.unavailable=true
        val task=PartitionDownloadFactory(f.pool,DataSource.Factory { ByteArrayDataSource(payload) },
            {_,_,_ -> fail("No removal") },barrier=f.barrier).createDownloader(
            DownloadRequest.Builder("other",Uri.parse("https://unused.invalid/other.flac")).setCustomCacheKey("other").build())
        assertThrows(PartitionOwnershipUncertain::class.java) { task.download(null) }
        f.unavailable=false; f.unknown()
    }
    @Test fun migrationProgressReportsUnknownIdleNativeCloseBeforeSourceProjection() {
        val f=fixture(); f.pool.acquire(key).use { f.actual=it.cache }; f.unavailable=true
        val control=CacheMigrationControl(1000,nanoTime={0L})
        val publication=CacheMigrationPublication(f.catalog,f.journal,{ throw AssertionError("Target must not open") })
        assertThrows(PartitionOwnershipUncertain::class.java) {
            BarrierCacheMigration(f.barrier,f.pool::trimIdle).run(control,f.legacy,"other",publication,{Long.MAX_VALUE},{})
        }
        assertEquals(MigrationWorkPhase.Uncertain,control.progress.phase)
        f.unavailable=false; f.unknown(); assertNull(f.journal.find("other"))
    }
    @Test fun preNativeAdmissionFailureWithKnownClosureStillReleasesGateAndCapacity() {
        val f=fixture(); val uid=requireNotNull(f.journal.ready(key)?.targetUid)
        assertTrue(File(requireNotNull(f.bytes),"${java.lang.Long.toHexString(uid)}.uid").delete())
        val reader=f.audio().source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec()) }
        reader.close(); assertEquals(0,f.budget.resident); assertEquals(0,f.pool.resident)
        assertEquals(0,f.barrier.active); f.barrier.exclusive().close()
        assertArrayEquals(payload,requireNotNull(f.legacy.getCachedSpans(key).single().file).readBytes())
    }
}
