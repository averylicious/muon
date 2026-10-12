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
    @Test fun actualPreparedManagerIndexCursorKeepsGlobalGateUntilClose() {
        val f=fixture(); val cursor=f.manager.downloadIndex.getDownloads()
        assertEquals(1,f.barrier.active)
        assertFalse(cursor.moveToNext()) // Empty/EOF is still an actual open native cursor.
        assertThrows(IOException::class.java) { f.barrier.exclusive() }
        cursor.close(); assertTrue(f.barrier.quiescent)
        f.barrier.exclusive().close()
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
    private fun <T> onWorker(work:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        return try { executor.submit<T> { work() }.get(10,TimeUnit.SECONDS) }
        catch(failure:java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) }
        finally { executor.shutdownNow() }
    }
    private fun completed(f:Fixture,key:String="saved/remove"):DownloadRequest {
        val request=request(key); val command=f.owner.prepare(request)
        assertTrue(f.owner.deliver(command,request) { it.addDownload(request) })
        await(f.manager) { f.index.getDownload(key)?.state==Download.STATE_COMPLETED }
        return request
    }
    @Test fun completedDeleteRefusesReaderAtEofThenRemovesBytesAndRowWithoutManagerRemoveTask() {
        val f=fixture(); val request=completed(f)
        val reader=f.session.audio.source.createDataSource()
        onWorker {
            reader.open(DataSpec.Builder().setUri(request.uri).setKey(request.id).build())
            val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4))
            assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT,reader.read(bytes,0,4))
        }
        try {
            assertEquals(OfflineStore.SavedRemoval.Busy,onWorker { f.session.removeCompleted(request.id) {true} })
            assertNotNull(f.index.getDownload(request.id))
        } finally { onWorker { reader.close() } }
        assertEquals(OfflineStore.SavedRemoval.Sent,onWorker { f.session.removeCompleted(request.id) {true} })
        assertNull(f.index.getDownload(request.id)); assertTrue(f.manager.currentDownloads.isEmpty())
        assertEquals(PartitionSavePhase.Closed,f.saves.find(request.id)?.phase)
        assertEquals(SavedCoverage.Missing,onWorker { f.session.audio.inspect(request.id).coverage })
        assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { f.session.removeCompleted(request.id) {true} })
        assertTrue(f.barrier.quiescent)
    }
    @Test fun completedDeletePreservesBytesForHugeAliasAndOtherShelfClaims() {
        val f=fixture(); val request=completed(f)
        val alias=DownloadRequest.Builder("older-alias",request.uri).setCustomCacheKey(request.id)
            .setData(ByteArray(8*1024*1024)).build()
        f.index.putDownload(Download(alias,Download.STATE_STOPPED,1,1,4,1,0))
        assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { f.session.removeCompleted(request.id) {true} })
        assertEquals(SavedCoverage.Full,onWorker { f.session.audio.inspect(request.id).coverage })
        provider.readableDatabase.rawQuery("SELECT length(data) FROM ExoPlayerDownloads${f.name} WHERE id=?",arrayOf(alias.id)).use {
            assertTrue(it.moveToFirst()); assertEquals(8*1024*1024,it.getInt(0))
        }
        f.index.removeDownload(alias.id)
        assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { f.session.removeCompleted(request.id) {false} })
        assertEquals(SavedCoverage.Full,onWorker { f.session.audio.inspect(request.id).coverage })
        assertNotNull(f.index.getDownload(request.id))
    }
    @Test fun completedDeleteCannotRemoveLegacyOrUnfinishedOrOversizedCompletedRecords() {
        val f=fixture()
        val old=request("old"); f.index.putDownload(Download(old,Download.STATE_COMPLETED,1,1,4,0,0))
        assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { f.session.removeCompleted(old.id) {true} })
        val pending=request("saved/pending"); val pendingCommand=f.owner.prepare(pending)
        assertTrue(f.owner.abandon(pendingCommand,pending))
        f.index.putDownload(Download(pending,Download.STATE_STOPPED,1,1,4,1,0))
        assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { f.session.removeCompleted(pending.id) {true} })
        val done=completed(f,"saved/oversized")
        provider.writableDatabase.execSQL("UPDATE ExoPlayerDownloads${f.name} SET data=zeroblob(?) WHERE id=?",
            arrayOf(8*1024*1024,done.id))
        assertEquals(OfflineStore.SavedRemoval.Failed,onWorker { f.session.removeCompleted(done.id) {true} })
        assertTrue(PartitionCompletionIndex(provider,f.name).containsId(done.id)); assertTrue(f.barrier.quiescent)
        assertThrows(IllegalStateException::class.java) { f.session.removeCompleted(done.id) {true} }
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
