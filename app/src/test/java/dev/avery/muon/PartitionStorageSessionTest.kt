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
    private inner class Fixture(commandCapacity:Int=DOWNLOAD_COMMAND_COUNT,nativeCapacity:Int=1,
        val barrier:SavedStorageBarrier=SavedStorageBarrier()) {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val migrations=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        val budget=PartitionNativeBudget(nativeCapacity)
        val legacyRoot=folders.newFolder()
        val legacy=SimpleCache(legacyRoot,NoOpCacheEvictor(),database).also { it.checkInitialization() }
        val name="storage_session_"+fixtures.size
        val index=DefaultDownloadIndex(database,name).also { it.getDownloads().close() }
        var available=true
        val native=PartitionNativeOwner(catalog,migrations,"card",{if(available) "card" else null},budget=budget,saves=saves)
        var countProviderReads=false;var providerReads=0
        private val trackedDatabase=object:androidx.media3.database.DatabaseProvider {
            override fun getReadableDatabase():SQLiteDatabase {
                if(countProviderReads) providerReads++
                return database.readableDatabase
            }
            override fun getWritableDatabase():SQLiteDatabase=database.writableDatabase
        }
        val session=PartitionStorageSession(catalog,saves,migrations,native,trackedDatabase,name,LegacySavedAudio(legacy),
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
    private fun fixture(commandCapacity:Int=DOWNLOAD_COMMAND_COUNT,nativeCapacity:Int=1,barrier:SavedStorageBarrier=SavedStorageBarrier())=
        Fixture(commandCapacity,nativeCapacity,barrier).also(fixtures::add)
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
    private fun <T> worker(read:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return executor.submit<T> {read()}.get(30,TimeUnit.SECONDS)}
        finally {executor.shutdownNow(); check(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun played(f:Fixture,key:String)=f.session.copyPlayed(spec(key),byteArrayOf(1),
        "http://127.0.0.1:7814/1",DataSource.Factory {ByteArrayDataSource(payload)}) {8192L}
    private fun completedLocal(f:Fixture,key:String,bytes:ByteArray) = worker {
        val source=DataSource.Factory {ByteArrayDataSource(bytes)}
        val metadata=androidx.media3.datasource.cache.DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(),bytes.size.toLong()))
        val ticket=f.session.copyLocalSaved(spec(key).buildUpon().setLength(bytes.size.toLong()).build(),metadata,source,{})
        val permit=f.barrier.exclusive()
        try {
            val request=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1")).setCustomCacheKey(key).build()
            f.session.publishLocalSaved(ticket,request,bytes.size.toLong(),metadata,source,permit,{}, {true},10)
        } finally {permit.close()}
    }
    private fun destinationHasBytes(f:Fixture,key:String):Boolean {
        val allocation=f.catalog.find(key) ?: return false
        return f.catalog.directory(allocation).walkTopDown().any {it.name.endsWith(".exo") && it.length()>0}
    }
    @Test fun savedCopyCompletionQueriesAreBoundedByOpensRatherThanReadBlocks() {
        val gate=SavedStorageBarrier();val source=fixture(nativeCapacity=2,barrier=gate);val target=fixture(nativeCapacity=2,barrier=gate)
        fun observe(key:String,bytes:ByteArray):Pair<Int,Int> {
            completedLocal(source,key,bytes);var live=0
            source.providerReads=0;source.countProviderReads=true
            val copy=try {worker {source.session.copyCompletedSavedTo(target.session,key,key+"-copy",true) {live++}}}
                finally {source.countProviderReads=false}
            assertEquals(key,copy.completion.key);assertEquals(bytes.size.toLong(),copy.completion.length)
            assertNull(target.index.getDownload(key+"-copy")) // Closed preparation is not publication.
            assertEquals(0,source.budget.resident);assertEquals(0,target.budget.resident);assertTrue(gate.quiescent)
            return source.providerReads to live
        }
        val small=observe("saved/source-short",byteArrayOf(1,2,3,4))
        val large=observe("saved/source-multi",ByteArray(5*LOCAL_SAVED_READ_BYTES+17) {it.toByte()})
        assertTrue(small.first>0);assertEquals(small.first,large.first)
        assertTrue("Actual storage/cancellation checks must grow with physical reads",large.second>small.second)
    }
    @Test fun completionChangingAfterActualCopiedBytesRefusesReceiptAndPreservesSource() {
        val gate=SavedStorageBarrier();val source=fixture(nativeCapacity=2,barrier=gate);val target=fixture(nativeCapacity=2,barrier=gate)
        val key="saved/source-mutated";val fresh="saved/copy-mutated";val bytes=ByteArray(3*LOCAL_SAVED_READ_BYTES+17) {it.toByte()}
        completedLocal(source,key,bytes);var changed=false
        assertThrows(IOException::class.java) {worker {
            source.session.copyCompletedSavedTo(target.session,key,fresh,true) {
                if(!changed && destinationHasBytes(target,fresh)) {
                    changed=true
                    val request=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
                        .setCustomCacheKey(key).setData(byteArrayOf(9)).build()
                    val progress=androidx.media3.exoplayer.offline.DownloadProgress().apply {
                        bytesDownloaded=bytes.size.toLong();percentDownloaded=100f
                    }
                    source.index.putDownload(Download(request,Download.STATE_COMPLETED,10,10,bytes.size.toLong(),0,0,progress))
                }
            }
        }}
        assertTrue(changed);assertArrayEquals(byteArrayOf(9),requireNotNull(source.index.getDownload(key)).request.data)
        assertNull(target.index.getDownload(fresh));assertEquals(0,source.budget.resident);assertEquals(0,target.budget.resident)
        worker {assertEquals(SavedCoverage.Full,source.session.audio.inspect(key).coverage);assertFalse(target.session.audio.contains(fresh))}
        val allocation=requireNotNull(source.catalog.find(key));val actual=java.io.ByteArrayOutputStream()
        source.catalog.directory(allocation).walkTopDown().filter {it.name.endsWith(".exo")}.forEach {actual.write(it.readBytes())}
        assertArrayEquals(bytes,actual.toByteArray());assertTrue(gate.quiescent)
    }
    @Test fun cancellationAfterActualDestinationWriteStillClosesOwnersAndKeepsSource() {
        val gate=SavedStorageBarrier();val source=fixture(nativeCapacity=2,barrier=gate);val target=fixture(nativeCapacity=2,barrier=gate)
        val key="saved/source-cancelled";val fresh="saved/copy-cancelled";val bytes=ByteArray(3*LOCAL_SAVED_READ_BYTES+17)
        val before=completedLocal(source,key,bytes);var cancelled=false
        assertThrows(java.io.InterruptedIOException::class.java) {worker {
            source.session.copyCompletedSavedTo(target.session,key,fresh,true) {
                if(destinationHasBytes(target,fresh)) {cancelled=true;throw java.io.InterruptedIOException("Cancel after actual destination write")}
            }
        }}
        assertTrue(cancelled);assertEquals(before,PartitionCompletionIndex(database,source.name).find(key))
        assertNull(target.index.getDownload(fresh));assertEquals(0,source.budget.resident);assertEquals(0,target.budget.resident)
        worker {assertEquals(SavedCoverage.Full,source.session.audio.inspect(key).coverage);assertFalse(target.session.audio.contains(fresh))}
        assertTrue(gate.quiescent)
    }

    @Test fun moveDestinationCompletedRowWorkIsBoundedIndependentlyOfAudioBlockCount() {
        val f=fixture(nativeCapacity=2)
        fun observe(key:String,bytes:ByteArray):Pair<Int,Int> = worker {
            val source=DataSource.Factory {ByteArrayDataSource(bytes)}
            val metadata=androidx.media3.datasource.cache.DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(),bytes.size.toLong()))
            val ticket=f.session.copyLocalSaved(spec(key).buildUpon().setLength(bytes.size.toLong()).build(),metadata,source,{})
            val permit=f.barrier.exclusive()
            try {
                val request=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1")).setCustomCacheKey(key).build()
                val completed=f.session.publishLocalSaved(ticket,request,bytes.size.toLong(),metadata,source,permit,{}, {true},10)
                var live=0;f.providerReads=0;f.countProviderReads=true
                val result=try {f.session.moveDestinationIdentity(ticket,completed,"card","fixture-root",permit) {live++}}
                    finally {f.countProviderReads=false}
                assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)},result.audioDigest)
                assertEquals(0,f.budget.resident) // Real digest reader/native owner closed before return.
                f.providerReads to live
            } finally {permit.close()}
        }
        val small=observe("saved/short-observation",byteArrayOf(1,2,3,4))
        val large=observe("saved/multiblock-observation",ByteArray(5*LOCAL_SAVED_READ_BYTES+17) {it.toByte()})
        assertTrue(small.first>0);assertEquals(small.first,large.first)
        assertTrue("Fresh per-block storage/cancellation checks must still grow with actual reads",large.second>small.second)
        assertTrue(f.barrier.quiescent)
    }
    @Test fun interleavedManualDeletionRecensusesBeforeResizeCanOverEvict() {
        val f=fixture(nativeCapacity=2); val keys=(0..2).map {playedKey("saved/overlap-$it")}
        worker {keys.forEach {played(f,it)}}
        var deleted=false
        val result=worker {f.session.resizePlayed(8,{true}) {
            if(!deleted && f.barrier.quiescent) {
                deleted=true
                assertEquals(OfflineStore.SavedRemoval.Sent,f.session.removePlayed(keys.last()) {true})
            }
        }}
        assertTrue(deleted); assertTrue(result.complete); assertEquals(0L,result.removed)
        assertEquals(8L,result.remainingBytes)
        worker {assertArrayEquals(payload,read(f,keys[0])); assertArrayEquals(payload,read(f,keys[1]))}
    }
    @Test fun resizeRebuildsStaleDerivedRowsAndRefusesSessionCloseBetweenOperations() {
        val f=fixture(nativeCapacity=2); val keys=(0..1).map {playedKey("saved/rebuild-$it")}
        worker {
            keys.forEach {played(f,it)}
            DiskPlayedSpanOrder(f.catalog.playedOrderFile).use {old ->
                old.initialized(); old.add(androidx.media3.datasource.cache.CacheSpan("played:foreign",0,0,0,null))
            }
        }
        var refused=false
        val result=worker {f.session.resizePlayed(4,{true}) {
            if(f.barrier.quiescent) {
                assertThrows(IOException::class.java) {f.session.close()}; refused=true
            }
        }}
        assertTrue(refused); assertTrue(result.complete); assertEquals(1L,result.removed)
        assertEquals(4L,result.remainingBytes); assertEquals(4L,worker {f.session.playedBytes()})
    }
    @Test fun resizeRefusesDerivedSymlinkWithoutChangingForeignFileOrOwnedAudio() {
        val f=fixture(); val key=playedKey("saved/link-protected")
        worker {played(f,key)}
        val foreign=folders.newFile().also {it.writeText("foreign data")}
        java.nio.file.Files.createSymbolicLink(f.catalog.playedOrderFile.toPath(),foreign.toPath())
        val result=worker {f.session.resizePlayed(0,{true})}
        assertFalse(result.complete); assertEquals(0L,result.removed)
        assertEquals("foreign data",foreign.readText()); assertArrayEquals(payload,worker {read(f,key)})
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
