@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionShelfManagerTest {
    @get:Rule val folders=TemporaryFolder()
    private val provider by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val fixtures=mutableListOf<Fixture>()
    private val payload=byteArrayOf(2,4,6,8)
    private inner class Fixture {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val migrations=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        val legacy=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),provider).also { it.checkInitialization() }
        val name="manager_owner_"+fixtures.size
        val index=DefaultDownloadIndex(provider,name).also { it.getDownloads().close() }
        val barrier=SavedStorageBarrier(); var available=true
        val native=PartitionNativeOwner(catalog,migrations,"volume",{if(available) "volume" else null},saves=saves)
        val session=PartitionStorageSession(catalog,saves,migrations,native,provider,name,LegacySavedAudio(legacy),
            DataSource.Factory { ByteArrayDataSource(payload) },{key -> !LegacySavedAudio(legacy).contains(key)},
            {available},barrier,commandCapacity=1)
        val owner=PartitionShelfManager(RuntimeEnvironment.getApplication(),provider,name,session,barrier)
        val manager=owner.initialize()
        init { manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads(); await(manager) { true } }
    }
    private fun fixture()=Fixture().also(fixtures::add)
    private fun request(key:String="saved/exact",data:ByteArray=byteArrayOf(3,5))=
        DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1")).setCustomCacheKey(key).setData(data).build()
    private fun await(manager:DownloadManager,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(manager.isInitialized && manager.isIdle && condition()) return
            check(System.nanoTime()<end) { "Owned manager did not settle" }; Thread.sleep(5)
        }
    }
    @After fun close() {
        try { fixtures.asReversed().forEach {
            // Fixture teardown may explicitly release the raw manager returned to a simulated
            // service; production must never leave a live static helper bound to a released owner.
            it.manager.release(); it.session.close(); it.legacy.release(); it.saves.close(); it.migrations.close(); it.catalog.close()
        } } finally { provider.close() }
    }
    @Test fun realManagerDeliveryHoldsGateAndCompletesExactSavedBytesThenNextCommandCanFit() {
        val f=fixture(); val first=request(); val command=f.owner.prepare(first)
        assertTrue(f.owner.deliver(command,first) { actual ->
            assertSame(f.manager,actual)
            assertThrows(IOException::class.java) { f.barrier.exclusive() }
            actual.addDownload(first)
        })
        await(f.manager) { f.index.getDownload(first.id)?.state==Download.STATE_COMPLETED }
        val reader=f.session.audio.source.createDataSource()
        try {
            assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(first.id).build()))
            val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
            assertThrows(IOException::class.java) { f.owner.close() }
        } finally { reader.close() }
        assertNotNull(f.owner.prepare(request("saved/next")))
        f.owner.close(); assertThrows(IOException::class.java) { f.owner.initialize() }
        assertEquals(first,f.index.getDownload(first.id)?.request)
    }
    @Test fun existingHugeRetainedPayloadRefusesBeforeHydrationOrRealManagerMutation() {
        val f=fixture(); val incoming=request(); val command=f.owner.prepare(incoming)
        val old=request(data=ByteArray(8*1024*1024))
        f.index.putDownload(Download(old,Download.STATE_STOPPED,1,1,4,1,0))
        var forwarded=false
        assertFalse(f.owner.deliver(command,incoming) { forwarded=true; it.addDownload(incoming) })
        assertFalse(forwarded)
        val table="ExoPlayerDownloads"+f.name
        provider.readableDatabase.rawQuery("SELECT state,length(data) FROM $table WHERE id=?",arrayOf(old.id)).use {
            assertTrue(it.moveToFirst()); assertEquals(Download.STATE_STOPPED,it.getInt(0)); assertEquals(8*1024*1024,it.getInt(1))
        }
        assertNotNull(f.saves.find(incoming.id)); assertFalse(f.owner.remove(old.id))
        assertNotNull(f.owner.prepare(request("saved/next")))
    }
    @Test fun callbackThrowAfterRealAddIsUnconfirmedAndDoesNotEraseItsReservedOwner() {
        val f=fixture(); val first=request(); val command=f.owner.prepare(first)
        assertThrows(IOException::class.java) { f.owner.deliver(command,first) {
            it.addDownload(first); throw IOException("service threw after enqueue")
        } }
        assertFalse(f.owner.abandon(command,first))
        await(f.manager) { f.index.getDownload(first.id)?.state==Download.STATE_COMPLETED }
        assertEquals(first,f.index.getDownload(first.id)?.request)
        assertEquals(PartitionSavePhase.Closed,f.saves.find(first.id)?.phase)
        assertNotNull(f.owner.prepare(request("saved/next")))
    }
    @Test fun serviceBindingNeverChangesManagerAndPreventsLiveHelperReplacement() {
        val f=fixture()
        assertSame(f.manager,f.owner.managerForService()); assertSame(f.manager,f.owner.managerForService())
        assertThrows(IOException::class.java) { f.owner.close() }
        assertSame(f.manager,f.owner.initialize()); assertTrue(f.manager.isInitialized)
    }
    @Test fun wrongGateAndChangedExactRequestCannotMutateTheManager() {
        val f=fixture(); assertThrows(IllegalArgumentException::class.java) {
            PartitionShelfManager(RuntimeEnvironment.getApplication(),provider,"other",f.session,SavedStorageBarrier())
        }
        val original=request(); val command=f.owner.prepare(original); var called=false
        assertFalse(f.owner.deliver(command,request(data=byteArrayOf(3,9))) { called=true })
        assertFalse(called); assertNull(f.index.getDownload(original.id)); assertTrue(f.owner.abandon(command,original))
    }
}
