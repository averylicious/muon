@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
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
class PartitionStorageSessionTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val fixtures=mutableListOf<Fixture>()
    private val managers=mutableListOf<DownloadManager>()
    private val payload=byteArrayOf(2,4,6,8)
    private inner class Fixture(commandCapacity:Int=DOWNLOAD_COMMAND_COUNT,nativeCapacity:Int=1) {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val migrations=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        val budget=PartitionNativeBudget(nativeCapacity); val barrier=SavedStorageBarrier()
        val legacyRoot=folders.newFolder()
        val legacy=SimpleCache(legacyRoot,NoOpCacheEvictor(),database).also { it.checkInitialization() }
        val name="storage_session_"+fixtures.size
        val index=DefaultDownloadIndex(database,name).also { it.getDownloads().close() }
        var available=true
        val native=PartitionNativeOwner(catalog,migrations,"card",{if(available) "card" else null},budget=budget,saves=saves)
        val session=PartitionStorageSession(catalog,saves,migrations,native,database,name,LegacySavedAudio(legacy),
            DataSource.Factory { ByteArrayDataSource(payload) },
            { key -> legacy.getCachedBytes(key,0,Long.MAX_VALUE)==0L && index.getDownload(key)==null },{available},barrier,commandCapacity)
        fun seed(key:String):File {
            val hole=requireNotNull(legacy.startReadWrite(key,0,payload.size.toLong()))
            val file:File
            try { file=legacy.startFile(key,0,payload.size.toLong()); file.writeBytes(payload); legacy.commitFile(file,payload.size.toLong()) }
            finally { legacy.releaseHoleSpan(hole) }
            legacy.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),payload.size.toLong()))
            return file
        }
        fun migrate(key:String)=session.migrate(CacheMigrationControl(1000,nanoTime={0L}),legacy,key,{Long.MAX_VALUE},{})
    }
    private fun fixture(commandCapacity:Int=DOWNLOAD_COMMAND_COUNT,nativeCapacity:Int=1)=Fixture(commandCapacity,nativeCapacity).also(fixtures::add)
    private fun spec(key:String)=DataSpec.Builder().setUri("muon-saved:test").setKey(key).build()
    private fun read(f:Fixture,key:String):ByteArray {
        val reader=f.session.audio.source.createDataSource()
        try {
            assertEquals(payload.size.toLong(),reader.open(spec(key)))
            val bytes=ByteArray(payload.size); assertEquals(bytes.size,reader.read(bytes,0,bytes.size))
            assertEquals(-1,reader.read(ByteArray(1),0,1)); return bytes
        } finally { reader.close() }
    }
    private fun await(manager:DownloadManager,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(manager.isInitialized && manager.isIdle && condition()) return
            check(System.nanoTime()<end) { "Session manager did not settle" }; Thread.sleep(5)
        }
    }
    @After fun close() {
        managers.asReversed().forEach { it.release() }
        try { fixtures.asReversed().forEach { it.session.close(); it.legacy.release(); it.saves.close(); it.migrations.close(); it.catalog.close() } }
        finally { database.close() }
    }
    @Test fun composedReadonlyLegacyMigrationNeverReopensFullNativeSourceAndKeepsOriginal() {
        val f=fixture(); val original=f.seed("readonly"); val uid=f.legacy.uid; f.legacy.release()
        SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).use { read ->
            val ready=f.session.migrateLegacy(CacheMigrationControl(1000,nanoTime={0L}),f.legacyRoot,uid,"readonly",read,{Long.MAX_VALUE},{
                assertFalse(SimpleCache.isCacheFolderLocked(f.legacyRoot))
                assertThrows(IOException::class.java) { f.barrier.shared() }
            })
            assertEquals(MigrationPhase.Ready,ready.phase)
        }
        assertArrayEquals(payload,read(f,"readonly")); assertArrayEquals(payload,original.readBytes())
        assertFalse(SimpleCache.isCacheFolderLocked(f.legacyRoot))
    }
    @Test fun composedLegacyMigrationAndPublishedRoutesPreserveOriginalBytes() {
        val f=fixture(); val original=f.seed("old")
        assertArrayEquals(payload,read(f,"old")); assertEquals(0,f.budget.resident)
        assertEquals(MigrationPhase.Ready,f.migrate("old").phase)
        assertArrayEquals(payload,read(f,"old")); assertArrayEquals(payload,original.readBytes())
        f.session.close(); assertEquals(0,f.budget.resident)
        assertNotNull(f.migrations.ready("old")) // Borrowed journal remains open.
    }
    @Test fun composedManagerCompletesDurableSaveAndSameSessionReadsBoundedIndexRoute() {
        val f=fixture(); val manager=DownloadManager(RuntimeEnvironment.getApplication(),f.index,f.session.downloaders).also(managers::add)
        manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads()
        val request=DownloadRequest.Builder("saved/new",Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey("saved/new").build()
        val command=f.session.prepare(request)
        assertTrue(f.session.forward(command,request,null)); f.session.delivered(command,true); manager.addDownload(request)
        await(manager) { f.index.getDownload(request.id)?.state==Download.STATE_COMPLETED }
        assertTrue(f.session.terminal(requireNotNull(f.index.getDownload(request.id))))
        assertEquals(PartitionSavePhase.Closed,f.saves.find(request.id)?.phase)
        assertArrayEquals(payload,read(f,request.id)); assertTrue(f.barrier.quiescent)
    }
    @Test fun knownPreForwardRefusalRetiresOnlyExactPreparedTokenAndPreservesReservedOwnership() {
        val f=fixture(1)
        fun request(key:String,data:ByteArray=byteArrayOf(3,5))=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey(key).setData(data).build()
        val first=request("saved/refused"); val token=f.session.prepare(first); val reservation=f.saves.find(first.id)
        assertFalse(f.session.abandon(token,request(first.id,byteArrayOf(3,9))))
        assertTrue(f.session.abandon(token,first)); assertFalse(f.session.abandon(token,first))
        assertEquals(reservation,f.saves.find(first.id)); assertNotNull(f.catalog.find(first.id))
        assertThrows(IOException::class.java) { f.session.prepare(first) }
        val next=request("saved/next"); val command=f.session.prepare(next)
        assertTrue(f.session.forward(command,next,null)); f.session.delivered(command,false,unconfirmed=true)
        assertFalse(f.session.abandon(command,next))
        assertThrows(PartitionCacheBusy::class.java) { f.session.prepare(request("saved/another")) }
        assertNull(f.index.getDownload(first.id)); assertNull(f.index.getDownload(next.id))
    }
    @Test fun missingCompletionCallbackCanReconcileExactPersistedRowBeforeNextSaveWithinOneReceiptBudget() {
        val f=fixture(commandCapacity=1,nativeCapacity=2); val manager=DownloadManager(RuntimeEnvironment.getApplication(),f.index,f.session.downloaders).also(managers::add)
        manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads()
        fun request(key:String)=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey(key).setData(byteArrayOf(3,5)).build()
        val first=request("saved/first"); val command=f.session.prepare(first)
        assertTrue(f.session.forward(command,first,null)); f.session.delivered(command,true); manager.addDownload(first)
        await(manager) { f.index.getDownload(first.id)?.state==Download.STATE_COMPLETED }
        val before=requireNotNull(f.index.getDownload(first.id)); val saved=f.saves.find(first.id)
        // Deliberately no terminal callback. The next real preparation reconciles its bounded row.
        val second=request("saved/second"); val next=f.session.prepare(second)
        assertEquals(saved,f.saves.find(first.id)); assertEquals(before.request,f.index.getDownload(first.id)?.request)
        assertArrayEquals(payload,read(f,first.id))
        assertTrue(f.session.forward(next,second,null)); f.session.delivered(next,true); manager.addDownload(second)
        await(manager) { f.index.getDownload(second.id)?.state==Download.STATE_COMPLETED }
        assertEquals(1,f.session.reconcileCompleted()); assertEquals(0,f.session.reconcileCompleted())
        assertArrayEquals(payload,read(f,second.id)); assertTrue(f.barrier.quiescent)
    }
    @Test fun closedJournalWithoutExactCompletedRequestNeverFreesItsSubmittedReceipt() {
        val f=fixture(1); val manager=DownloadManager(RuntimeEnvironment.getApplication(),f.index,f.session.downloaders).also(managers::add)
        manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads()
        val request=DownloadRequest.Builder("saved/first",Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey("saved/first").setData(byteArrayOf(3,5)).build()
        val command=f.session.prepare(request)
        assertTrue(f.session.forward(command,request,null)); f.session.delivered(command,true); manager.addDownload(request)
        await(manager) { f.index.getDownload(request.id)?.state==Download.STATE_COMPLETED }
        val original=requireNotNull(f.index.getDownload(request.id))
        val other=DownloadRequest.Builder(request.id,request.uri)
            .setCustomCacheKey(request.customCacheKey).setData(byteArrayOf(3,9)).build()
        f.index.putDownload(Download(other,Download.STATE_COMPLETED,original.startTimeMs,original.updateTimeMs,
            original.contentLength,0,0))
        assertEquals(0,f.session.reconcileCompleted())
        val next=DownloadRequest.Builder("saved/second",request.uri).setCustomCacheKey("saved/second").build()
        assertThrows(PartitionCacheBusy::class.java) { f.session.prepare(next) }
        assertNull(f.saves.find(next.id)); assertEquals(other,f.index.getDownload(request.id)?.request)
        f.index.putDownload(original)
        assertEquals(1,f.session.reconcileCompleted()); assertArrayEquals(payload,read(f,request.id))
    }
    @Test fun shutdownRefusesReaderAtEofAndIdleReaderCannotReopenClosedSession() {
        val f=fixture(); f.seed("old"); val reader=f.session.audio.source.createDataSource()
        val idle=f.session.audio.source.createDataSource()
        reader.open(spec("old")); reader.read(ByteArray(4),0,4); assertEquals(-1,reader.read(ByteArray(1),0,1))
        assertThrows(IOException::class.java) { f.session.close() }
        reader.close(); f.session.close()
        assertThrows(IOException::class.java) { idle.open(spec("old")) }; idle.close()
        assertTrue(f.barrier.quiescent); assertTrue(f.legacy.cacheSpace>0)
    }
    @Test fun idleNativeRetirementAllowsSecondMigrationWithinOneInstanceBudget() {
        val f=fixture(); val a=f.seed("a"); val b=f.seed("b")
        f.migrate("a"); assertArrayEquals(payload,read(f,"a")); assertEquals(1,f.budget.resident)
        f.migrate("b"); assertEquals(0,f.budget.resident)
        assertArrayEquals(payload,read(f,"b")); assertEquals(1,f.budget.resident)
        assertArrayEquals(payload,a.readBytes()); assertArrayEquals(payload,b.readBytes())
    }
    @Test fun migrationCannotReplaceReservedNewSaveAndRefusalKeepsSessionUsable() {
        val f=fixture(); val key="saved/reserved"
        val request=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/file/1")).setCustomCacheKey(key).build()
        val command=f.session.prepare(request)
        assertThrows(IOException::class.java) { f.migrate(key) }
        assertNull(f.migrations.find(key)); assertEquals(command.ticket,f.saves.find(key)?.ticket)
        assertTrue(f.barrier.quiescent); assertTrue(f.session.forward(command,request,null))
    }
}
