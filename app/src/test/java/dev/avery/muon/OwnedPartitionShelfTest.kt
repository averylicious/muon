@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
class OwnedPartitionShelfTest {
    @get:Rule val folders=TemporaryFolder()
    private val payload=byteArrayOf(2,4,6,8)
    private inner class Fixture(val oldKey:String="old") {
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val legacy=folders.newFolder(); val parent=folders.newFolder(); val root=File(parent,"partition-shelf")
        val databaseFile:File; val original:File
        val barrier=SavedStorageBarrier(); val budget=PartitionNativeBudget(2); var volume:String?="fixture"
        init {
            val native=SimpleCache(legacy,NoOpCacheEvictor(),provider)
            try {
                native.checkInitialization(); val hole=requireNotNull(native.startReadWrite(oldKey,0,4))
                try { original=native.startFile(oldKey,0,4); original.writeBytes(payload); native.commitFile(original,4) }
                finally { native.releaseHoleSpan(hole) }
                native.applyContentMetadataMutations(oldKey,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
                databaseFile=File(provider.readableDatabase.path)
            } finally { native.release() }
        }
        fun owner(start:PartitionShelfStart=PartitionShelfStart.Fresh,path:File=root,currentVolume:()->String?={volume})=OwnedPartitionShelf(
            RuntimeEnvironment.getApplication(),path,legacy,databaseFile,provider,"owned_partition","fixture",currentVolume,
            start,barrier,{true},DataSource.Factory { ByteArrayDataSource(payload) },budget)
    }
    private fun await(manager:DownloadManager,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(manager.isInitialized && manager.isIdle && condition()) return
            check(System.nanoTime()<end) { "Owned shelf manager did not settle" }; Thread.sleep(5)
        }
    }
    /** Only test-owned, known healthy and unattached resources are closed; no production reset API. */
    private fun teardown(owner:OwnedPartitionShelf) {
        val startupField=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply { isAccessible=true }
        val startup=startupField.get(owner)
        val opened=StorageStartup::class.java.getDeclaredField("opened").apply { isAccessible=true }.get(startup) ?: return
        (opened.javaClass.getDeclaredField("workers").apply { isAccessible=true }.get(opened) as java.util.concurrent.ExecutorService).shutdown()
        for(name in listOf("manager","session","legacy","saves","migrations","catalog")) {
            val resource=opened.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(opened)
            (resource as java.io.Closeable).close()
        }
        onWorker {(opened.javaClass.getDeclaredField("retirements").apply {isAccessible=true}.get(opened) as LegacyRetirementJournal).close()}
        onWorker {(opened.javaClass.getDeclaredField("moves").apply {isAccessible=true}.get(opened) as PartitionSavedMoveJournal).close()}
        onWorker {(opened.javaClass.getDeclaredField("deletes").apply {isAccessible=true}.get(opened) as PartitionSavedDeleteJournal).close()}
    }
    @Test fun completeShelfStartsWithoutLegacyNativeCacheAndRoutesOriginalMigratedAndNewSavedBytes() {
        val f=Fixture(); val owner=f.owner()
        try {
            onWorker {owner.prepare()}; onWorker {owner.prepare()}; assertEquals(0,f.budget.resident)
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            val manager=owner.initializeManager(); manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads()
            await(manager) { true }; assertSame(manager,owner.initializeManager())
            val ready=owner.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{
                assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            })
            assertEquals(MigrationPhase.Ready,ready.phase)
            val reader=owner.audio.source.createDataSource()
            try {
                assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey("old").build()))
                val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
            } finally { reader.close() }
            val request=DownloadRequest.Builder("saved/new",Uri.parse("http://127.0.0.1:7814/api1/fileopus/1")).setCustomCacheKey("saved/new").build()
            val token=owner.prepareSave(request)
            assertTrue(owner.deliver(token,request) { it.addDownload(request) })
            await(manager) { manager.downloadIndex.getDownload(request.id)?.state==Download.STATE_COMPLETED }
            assertTrue(owner.audio.contains(request.id))
            val saved=owner.audio.source.createDataSource()
            try {
                assertEquals(4L,saved.open(DataSpec.Builder().setUri("muon-saved:test").setKey(request.id).build()))
                val bytes=ByteArray(4); assertEquals(4,saved.read(bytes,0,4)); assertArrayEquals(payload,bytes)
            } finally { saved.close() }
            assertArrayEquals(payload,f.original.readBytes())
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy)); assertTrue(f.barrier.quiescent)
        } finally { teardown(owner); f.provider.close() }
    }
    private fun awaitMigration(owner:OwnedPartitionShelf) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(owner.migrationBusy) { check(System.nanoTime()<end) { "Migration worker did not settle" }; Thread.sleep(5) }
    }
    @Test fun ownedWorkerPublishesOnlyExplicitlyRequestedResourceOffMainAndPreservesOriginal() {
        val f=Fixture(); val mainThread=Thread.currentThread(); val offMain=java.util.concurrent.atomic.AtomicBoolean()
        val owner=f.owner(currentVolume={ if(Thread.currentThread()!==mainThread) offMain.set(true); f.volume })
        try {
            onWorker {owner.prepare()}
            assertFalse(owner.startMigration("old",30000,explicitConsent=false)); assertEquals(0,f.budget.resident)
            assertTrue(owner.startMigration("old",30000,explicitConsent=true)); awaitMigration(owner)
            assertEquals(MigrationWorkPhase.Ready,owner.migrationProgress.phase); assertTrue(offMain.get())
            CacheMigrationJournal(f.root,create=false).use { assertEquals(MigrationPhase.Ready,it.ready("old")?.phase) }
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertFalse(owner.cancelMigration()); assertTrue(f.barrier.quiescent)
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun pendingWorkerCancellationHasNoReservationAndNoSecondQueuedMigration() {
        val f=Fixture(); val owner=f.owner(); var pending:Runnable?=null
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { check(pending==null); pending=it },
                {control,key -> owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L})
            assertTrue(work.start("old",1000,true)); assertTrue(work.busy)
            assertFalse(work.start("other",1000,true)); assertTrue(work.cancel())
            requireNotNull(pending).run()
            assertEquals(MigrationWorkPhase.Cancelled,work.progress.phase); assertFalse(work.busy)
            CacheMigrationJournal(f.root,create=false).use { assertNull(it.find("old")) }
            assertEquals(0,f.budget.resident); assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun deadlineIncludesWaitingBeforeWorkerRunsWithoutClaimingOrOpeningTarget() {
        val f=Fixture(); val owner=f.owner(); var pending:Runnable?=null; var clock=0L
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { pending=it },
                {control,key -> owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={clock})
            assertTrue(work.start("old",1,true)); clock=2_000_000
            requireNotNull(pending).run()
            assertEquals(MigrationWorkPhase.Expired,work.progress.phase); assertFalse(work.busy)
            CacheMigrationJournal(f.root,create=false).use { assertNull(it.find("old")) }
            assertEquals(0,f.budget.resident); assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun knownRejectionDrainsButUnknownPostSubmissionKeepsItsSingleSlotAndCancelsCooperatively() {
        val f=Fixture(); val owner=f.owner(); var pending:Runnable?=null
        try {
            onWorker {owner.prepare()}
            val rejected=PartitionMigrationWork(java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException() },
                {control,key -> owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L},knownRejectionBeforeSubmission=true)
            assertFalse(rejected.start("old",1000,true)); assertFalse(rejected.busy)
            assertEquals(MigrationWorkPhase.Failed,rejected.progress.phase)
            val unknown=PartitionMigrationWork(java.util.concurrent.Executor { pending=it; throw IOException("Unknown submission return") },
                {control,key -> owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L})
            assertFalse(unknown.start("old",1000,true)); assertTrue(unknown.busy)
            assertEquals(MigrationWorkPhase.Uncertain,unknown.progress.phase)
            assertFalse(unknown.start("other",1000,true)); requireNotNull(pending).run()
            assertTrue(unknown.busy); assertEquals(MigrationWorkPhase.Uncertain,unknown.progress.phase)
            CacheMigrationJournal(f.root,create=false).use { assertNull(it.find("old")) }
            assertEquals(0,f.budget.resident); assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun acceptedThenRejectedRunnableNeverInvokesMigrationOrReleasesItsSlot() {
        val f=Fixture(); val owner=f.owner(); var pending:Runnable?=null; var migrations=0
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { pending=it; throw java.util.concurrent.RejectedExecutionException("Accepted first") },
                { control,key -> migrations++; owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L})
            assertFalse(work.start("old",1000,true)); assertTrue(work.busy)
            requireNotNull(pending).run(); requireNotNull(pending).run()
            assertEquals(0,migrations); assertTrue(work.busy); assertEquals(MigrationWorkPhase.Uncertain,work.progress.phase)
            assertFalse(work.start("other",1000,true))
            CacheMigrationJournal(f.root,create=false).use { assertNull(it.find("old")) }
            assertEquals(0,f.budget.resident); assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun inlineActualReadyThenExecutorThrowReportsKnownExecutionWithoutRewritingOutcome() {
        val f=Fixture(); val owner=f.owner(); var migrations=0
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { it.run(); throw IOException("After completion") },
                { control,key -> migrations++; owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L})
            assertTrue(work.start("old",1000,true)); assertFalse(work.busy)
            assertEquals(1,migrations); assertEquals(MigrationWorkPhase.Ready,work.progress.phase)
            CacheMigrationJournal(f.root,create=false).use { assertEquals(MigrationPhase.Ready,it.ready("old")?.phase) }
            assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun replayingAnAlreadyFinishedRunnableCannotBeginAnotherActualMigration() {
        val f=Fixture(); val owner=f.owner(); var pending:Runnable?=null; var migrations=0
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { pending=it },
                { control,key -> migrations++; owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L})
            assertTrue(work.start("old",1000,true)); requireNotNull(pending).run()
            val ready=CacheMigrationJournal(f.root,create=false).use { it.ready("old") }
            requireNotNull(pending).run()
            assertEquals(1,migrations); assertFalse(work.busy); assertEquals(MigrationWorkPhase.Ready,work.progress.phase)
            CacheMigrationJournal(f.root,create=false).use { assertEquals(ready,it.ready("old")) }
            assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun concurrentlyReplayedRunnableCannotEnterTheActualMigrationTwice() {
        val f=Fixture(); val owner=f.owner(); var pending:Runnable?=null
        val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val migrations=java.util.concurrent.atomic.AtomicInteger()
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { pending=it },
                { control,key ->
                    migrations.incrementAndGet(); entered.countDown()
                    check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
                    owner.migrate(control,key,{Long.MAX_VALUE},{})
                },{owner.isAvailable},nanoTime={0L})
            assertTrue(work.start("old",1000,true))
            val task=requireNotNull(pending); val first=worker.submit { task.run() }
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            task.run(); assertEquals(1,migrations.get())
            release.countDown(); first.get(5,java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(MigrationWorkPhase.Ready,work.progress.phase); assertFalse(work.busy)
            assertArrayEquals(payload,f.original.readBytes())
        } finally {
            release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))
            teardown(owner); f.provider.close()
        }
    }
    @Test fun inlineActualFailureThenExecutorThrowDoesNotPretendNoSubmissionOccurred() {
        val f=Fixture(); val owner=f.owner(); var migrations=0
        try {
            onWorker {owner.prepare()}
            val work=PartitionMigrationWork(java.util.concurrent.Executor { it.run(); throw java.util.concurrent.RejectedExecutionException("After execution") },
                { _,_ -> migrations++; throw IOException("Known operation failure") },{owner.isAvailable},nanoTime={0L})
            assertTrue(work.start("old",1000,true)); assertEquals(1,migrations)
            assertFalse(work.busy); assertEquals(MigrationWorkPhase.Failed,work.progress.phase)
            CacheMigrationJournal(f.root,create=false).use { assertNull(it.find("old")) }
            assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun resumedShelfReadsReadyCopyWithoutReopeningLegacyCacheOrInventingNewMigration() {
        val f=Fixture(); val first=f.owner(); var resumed:OwnedPartitionShelf?=null; var firstClosed=false
        try {
            onWorker {first.prepare()}
            val record=first.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})
            teardown(first); firstClosed=true; assertEquals(0,f.budget.resident)
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            val reader=next.audio.source.createDataSource()
            try {
                assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey("old").build()))
                val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
            } finally { reader.close() }
            CacheMigrationJournal(f.root).use { assertEquals(record,it.ready("old")) }
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy)); assertArrayEquals(payload,f.original.readBytes())
        } finally { resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close() }
    }
    @Test fun resumePinsJournalIdentityBeforeAnyConstructorOrLegacyOpening() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false
        try {
            onWorker {first.prepare()}; teardown(first); firstClosed=true
            val file=File(f.root,"partition-locators-v1.db"); val kept=File(f.root,"preserved-locators.db")
            var observations=0
            val resume=f.owner(PartitionShelfStart.Resume,currentVolume={
                observations++
                if(observations==2) { check(file.renameTo(kept)); kept.copyTo(file) }
                f.volume
            })
            assertThrows(IOException::class.java) { onWorker {resume.prepare()} }
            assertTrue(kept.isFile); assertTrue(file.isFile); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertArrayEquals(payload,f.original.readBytes()); assertEquals(0,f.budget.resident)
            assertThrows(IOException::class.java) { onWorker {resume.prepare()} }
        } finally { if(!firstClosed) teardown(first); f.provider.close() }
    }
    @Test fun resumeNeverCreatesAMissingJournalAndFreshNeverAdoptsExistingRoot() {
        val f=Fixture(); assertTrue(f.root.mkdir()); val marker=File(f.root,"user-original").apply { writeText("kept") }
        try {
            val fresh=f.owner(); assertThrows(IOException::class.java) { onWorker {fresh.prepare()} }
            assertEquals("kept",marker.readText()); assertArrayEquals(payload,f.original.readBytes())
            val resume=f.owner(PartitionShelfStart.Resume)
            assertThrows(IOException::class.java) { onWorker {resume.prepare()} }
            assertFalse(File(f.root,"new-save-journal-v1.db").exists())
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally { f.provider.close() }
    }
    @Test fun partitionRootCannotAliasTheLegacyFolder() {
        val f=Fixture(); val owner=f.owner(path=f.legacy)
        try {
            assertThrows(IOException::class.java) { onWorker {owner.prepare()} }
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertEquals(0,f.budget.resident)
        } finally { f.provider.close() }
    }
    private fun <T> onWorker(timeoutSeconds:Long=10,read:()->T):T {
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        try { return try { worker.submit<T> { read() }.get(timeoutSeconds,TimeUnit.SECONDS) }
            catch(failure:java.util.concurrent.ExecutionException) { throw requireNotNull(failure.cause) } }
        finally { worker.shutdownNow(); check(worker.awaitTermination(10,TimeUnit.SECONDS)) }
    }
    @Test fun restartedInterruptedMigrationRequiresExactConsentAndPreservesAllEarlierBytes() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        try {
            onWorker {first.prepare()}
            assertThrows(IOException::class.java) {
                first.migrate(CacheMigrationControl(10000,nanoTime={0L}),"old",{Long.MAX_VALUE},{
                    CacheMigrationJournal(f.root,create=false).use {
                        if(it.find("old")?.phase==MigrationPhase.Verified) throw IOException("Stopped before publish")
                    }
                })
            }
            val shown=CacheMigrationJournal(f.root,create=false).use { requireNotNull(it.find("old")) }
            assertEquals(MigrationPhase.Uncertain,shown.phase); assertEquals(0,f.budget.resident)
            val files=File(f.root,"resources/${shown.ticket.allocation.directory}").walkTopDown()
                .filter { it.isFile }.associateWith { it.readBytes() }
            assertTrue(files.isNotEmpty()); teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertThrows(IllegalStateException::class.java) { next.migrationRecoveryPage() }
            assertEquals(listOf(shown),onWorker { next.migrationRecoveryPage() })
            assertEquals(0,f.budget.resident); assertTrue(onWorker { next.audio.contains("old") })
            assertTrue(next.startMigration("old",30000,true)); awaitMigration(next)
            assertEquals(MigrationWorkPhase.Failed,next.migrationProgress.phase)
            CacheMigrationJournal(f.root,create=false).use { assertEquals(shown,it.find("old")) }
            assertFalse(next.retryMigration(shown,30000,false)); assertEquals(0,f.budget.resident)
            assertTrue(next.retryMigration(shown,30000,true)); awaitMigration(next)
            assertEquals(MigrationWorkPhase.Ready,next.migrationProgress.phase)
            val ready=CacheMigrationJournal(f.root,create=false).use { requireNotNull(it.ready("old")) }
            assertNotEquals(shown.ticket.allocation,ready.ticket.allocation)
            files.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertFalse(next.retryMigration(ready,30000,true))
            assertTrue(next.retryMigration(shown,30000,true)); awaitMigration(next)
            assertEquals(MigrationWorkPhase.Failed,next.migrationProgress.phase)
            CacheMigrationJournal(f.root,create=false).use { assertEquals(ready,it.ready("old")) }
        } finally { resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close() }
    }
    @Test fun recoveryPagesAreBoundedAndInterruptedSavesAreNotAdoptedAfterRestart() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        try {
            onWorker {first.prepare()}
            CachePartitionCatalog(f.root,create=false).use { catalog ->
                CacheMigrationJournal(f.root,create=false).use { migration ->
                    for(i in 0 until PARTITION_LOCATOR_WINDOW+3) {
                        val allocation=catalog.reserve("migration/$i"); migration.begin(allocation,123L,null)
                    }
                }
                PartitionSaveJournal(f.root,create=false).use { saves ->
                    for(i in 0 until PARTITION_LOCATOR_WINDOW+3) {
                        val ticket=saves.begin(catalog.reserve("saved/$i")); saves.opening(ticket)
                    }
                }
            }
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            val page=onWorker { next.migrationRecoveryPage() }
            assertEquals(PARTITION_LOCATOR_WINDOW,page.size)
            assertEquals(3,onWorker { next.migrationRecoveryPage(page.last().ticket.allocation.key) }.size)
            val saves=onWorker { next.saveRecoveryPage() }; assertEquals(PARTITION_LOCATOR_WINDOW,saves.size)
            assertTrue(saves.all { it.phase==PartitionSavePhase.Opening })
            assertEquals(3,onWorker { next.saveRecoveryPage(saves.last().ticket.allocation.key) }.size)
            assertFalse(onWorker { next.audio.contains(saves.first().ticket.allocation.key) })
            assertEquals(0,f.budget.resident); assertArrayEquals(payload,f.original.readBytes())
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally { resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close() }
    }

    @Test fun shelfPlayedCopyUsesItsMixedReadRouteAfterRestartWithoutLegacyNativeOrManagerRows() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        val key=playedKey("saved/owned-played"); val song=byteArrayOf(1,2,3)
        val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/api1/fileopus/7").setKey(key).build()
        try {
            onWorker {first.prepare()}; val manager=first.initializeManager(); await(manager) { true }
            onWorker { first.copyPlayed(spec,song,"http://127.0.0.1:7814/7",
                DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L } }
            assertEquals(0,f.budget.resident); assertTrue(onWorker { first.audio.contains(key) })
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            onWorker {
                val state=next.audio.inspect(key); assertEquals(SavedCoverage.Full,state.coverage)
                assertArrayEquals(song,state.metadata.get(SONG_METADATA,null as ByteArray?))
                val reader=next.audio.source.createDataSource()
                try {
                    assertEquals(payload.size.toLong(),reader.open(spec))
                    val bytes=ByteArray(payload.size); assertEquals(payload.size,reader.read(bytes,0,bytes.size))
                    assertArrayEquals(payload,bytes)
                } finally { reader.close() }
                val keys=ArrayList<String>(); next.audio.forEachKey { keys+=it; true }
                assertTrue(key in keys); assertTrue("old" in keys)
            }
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy)); assertArrayEquals(payload,f.original.readBytes())
        } finally { resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close() }
    }

    private fun copyPlayed(owner:OwnedPartitionShelf,key:String) {
        val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        onWorker { owner.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",
            DataSource.Factory { ByteArrayDataSource(payload) }) {8192L} }
    }
    @Test fun playedResizeUsesRealLastReadAndKeepsItsDeletionAfterRestart() {
        val f=Fixture(); val first=f.owner(); var closed=false; var resumed:OwnedPartitionShelf?=null
        val older=playedKey("saved/older"); val newer=playedKey("saved/newer")
        try {
            onWorker {first.prepare()}; await(first.initializeManager()) {true}
            copyPlayed(first,older); Thread.sleep(20); copyPlayed(first,newer); Thread.sleep(20)
            onWorker {
                val reader=first.audio.source.createDataSource()
                try {
                    reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(older).build())
                    assertEquals(4,reader.read(ByteArray(4),0,4))
                } finally {reader.close()}
            }
            val result=onWorker {first.resizePlayed(4) {true}}
            assertTrue(result.complete); assertEquals(1L,result.removed); assertEquals(4L,result.removedBytes)
            assertEquals(4L,result.remainingBytes)
            assertEquals(SavedCoverage.Full,onWorker {first.audio.inspect(older).coverage})
            assertEquals(SavedCoverage.Missing,onWorker {first.audio.inspect(newer).coverage})
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            teardown(first); closed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}; await(next.initializeManager()) {true}
            assertEquals(SavedCoverage.Full,onWorker {next.audio.inspect(older).coverage})
            assertEquals(SavedCoverage.Missing,onWorker {next.audio.inspect(newer).coverage})
            assertEquals(0L,onWorker {next.resizePlayed(4) {true}}.removed)
        } finally {resumed?.let(::teardown); if(!closed) teardown(first); f.provider.close()}
    }
    @Test fun playedResizeReportsProtectedLegacyBytesAndStopsAtRealActiveReader() {
        val f=Fixture(oldKey=playedKey("saved/protected-legacy")); val owner=f.owner()
        val key=playedKey("saved/resize-owned")
        try {
            onWorker {owner.prepare()}; await(owner.initializeManager()) {true}; copyPlayed(owner,key)
            val reader=owner.audio.source.createDataSource()
            onWorker {
                reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(key).build())
                assertEquals(4,reader.read(ByteArray(4),0,4))
                assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT,reader.read(ByteArray(4),0,4))
            }
            try {
                val busy=onWorker {owner.resizePlayed(0) {true}}
                assertFalse(busy.complete); assertEquals(0L,busy.removed); assertEquals(8L,busy.remainingBytes)
                assertArrayEquals(payload,f.original.readBytes())
            } finally {onWorker {reader.close()}}
            val protected=onWorker {owner.resizePlayed(0) {true}}
            assertFalse(protected.complete); assertEquals(1L,protected.removed); assertEquals(4L,protected.remainingBytes)
            assertEquals(SavedCoverage.Missing,onWorker {owner.audio.inspect(key).coverage})
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally {teardown(owner); f.provider.close()}
    }
    @Test fun playedResizePagesPastFirstWindowAndPreservesCallerClaims() {
        val f=Fixture(); val owner=f.owner(); val keep=playedKey("saved/resize-00")
        try {
            onWorker {owner.prepare()}; await(owner.initializeManager()) {true}
            for(i in 0..18) copyPlayed(owner,playedKey("saved/resize-"+i.toString().padStart(2,'0')))
            val result=onWorker(60) {owner.resizePlayed(4) {it!=keep}}
            assertTrue(result.complete); assertEquals(18L,result.removed); assertEquals(72L,result.removedBytes)
            assertEquals(4L,result.remainingBytes); assertEquals(SavedCoverage.Full,onWorker {owner.audio.inspect(keep).coverage})
            assertEquals(19,onWorker { owner.saveRecoveryPage().size+owner.saveRecoveryPage(owner.saveRecoveryPage().last().ticket.allocation.key).size })
            assertArrayEquals(payload,f.original.readBytes())
        } finally {teardown(owner); f.provider.close()}
    }
    @Test fun actualStoreBulkDeleteUsesOwnedMutationForCompletedLegacyAndNewRecords() {
        val f=Fixture(); val owner=f.owner(); val app=RuntimeEnvironment.getApplication()
        val field=OfflineStore::class.java.getDeclaredField("store").apply {isAccessible=true}; val previous=field.get(OfflineStore)
        try {
            val old=DownloadRequest.Builder("old",Uri.parse("http://127.0.0.1:7814/old")).setCustomCacheKey("old").build()
            DefaultDownloadIndex(f.provider,"owned_partition").putDownload(Download(old,Download.STATE_COMPLETED,1,2,4,0,0))
            onWorker {owner.prepare()}; val manager=owner.initializeManager()
            manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads(); await(manager) {true}
            val ids=(0..1).map {"saved/bulk-$it"}
            for(id in ids) {
                val request=DownloadRequest.Builder(id,Uri.parse("http://127.0.0.1:7814/$id")).setCustomCacheKey(id).build()
                val command=owner.prepareSave(request); assertTrue(owner.deliver(command,request) {it.addDownload(request)})
                await(manager) {manager.downloadIndex.getDownload(id)?.state==Download.STATE_COMPLETED}
            }
            val phone=Shelf.partitioned(owner,MuonDownloadService::class.java); val removed=ArrayList<String>()
            phone.partitionRemoved={id -> assertSame(Looper.getMainLooper(),Looper.myLooper()); removed+=id}
            val art=DownloadArt(folders.newFolder(),barrier=f.barrier)
            val artwork=DownloadArtworkWork(java.util.concurrent.Executor {it.run()},art::fetchEntry,art::removeEntry,barrier=f.barrier)
            val store=OfflineStore.Store(phone,art,PlayedSongEvictor(8192L) {},
                app.getSharedPreferences("bulk-removal-fixture",0),f.provider,{}, {},artwork=artwork,storageBarrier=f.barrier)
            field.set(OfflineStore,store)
            assertEquals(OfflineStore.RemoveAllPlan(0,0,removed=3),onWorker {OfflineStore.removeAllNow(app)})
            shadowOf(Looper.getMainLooper()).idle(); assertEquals((ids+"old").toSet(),removed.toSet())
            assertFalse(store.removalDelivery.busy); assertTrue(manager.isIdle)
            for(id in ids) {assertNull(manager.downloadIndex.getDownload(id)); assertFalse(onWorker {owner.audio.contains(id)})}
            assertNull(manager.downloadIndex.getDownload("old")); assertFalse(f.original.exists())
            assertFalse(onWorker {owner.audio.contains("old")})
            assertEquals(LegacyRetirementPhase.Complete,onWorker {owner.retirementRecoveryPage().single().phase})
            assertEquals(OfflineStore.RemoveAllPlan(0,0),onWorker {OfflineStore.removeAllNow(app)})
        } finally {field.set(OfflineStore,previous); teardown(owner); f.provider.close()}
    }
    @Test fun actualStoreBulkDeleteRefusesEofReaderThenSucceedsAfterClose() {
        val f=Fixture(); val owner=f.owner(); val app=RuntimeEnvironment.getApplication()
        val field=OfflineStore::class.java.getDeclaredField("store").apply {isAccessible=true}; val previous=field.get(OfflineStore)
        val id="saved/bulk-reader"
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager()
            manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads(); await(manager) {true}
            val request=DownloadRequest.Builder(id,Uri.parse("http://127.0.0.1:7814/file")).setCustomCacheKey(id).build()
            val command=owner.prepareSave(request); assertTrue(owner.deliver(command,request) {it.addDownload(request)})
            await(manager) {manager.downloadIndex.getDownload(id)?.state==Download.STATE_COMPLETED}
            val phone=Shelf.partitioned(owner,MuonDownloadService::class.java)
            val art=DownloadArt(folders.newFolder(),barrier=f.barrier);val artwork=coverWork(art,f.barrier)
            val store=OfflineStore.Store(phone,art,PlayedSongEvictor(8192L) {},
                app.getSharedPreferences("bulk-reader-fixture",0),f.provider,{}, {},artwork=artwork,storageBarrier=f.barrier)
            field.set(OfflineStore,store)
            val reader=owner.audio.source.createDataSource()
            try {
                onWorker {
                    reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(id).build())
                    assertEquals(4,reader.read(ByteArray(4),0,4)); assertEquals(-1,reader.read(ByteArray(1),0,1))
                }
                assertEquals(OfflineStore.RemoveAllPlan(0,0,later=1,busy=true),onWorker {OfflineStore.removeAllNow(app)})
                assertNotNull(manager.downloadIndex.getDownload(id)); assertFalse(store.removalDelivery.busy)
            } finally {onWorker {reader.close()}}
            assertEquals(OfflineStore.RemoveAllPlan(0,0,removed=1),onWorker {OfflineStore.removeAllNow(app)})
            assertNull(manager.downloadIndex.getDownload(id)); assertArrayEquals(payload,f.original.readBytes())
        } finally {field.set(OfflineStore,previous); teardown(owner); f.provider.close()}
    }
    @Test fun actualStoreCompletedDeletePublishesScalarRemovalAndCannotReappearAfterRestart() {
        val f=Fixture(); val first=f.owner(); var closed=false; var resumed:OwnedPartitionShelf?=null
        val field=OfflineStore::class.java.getDeclaredField("store").apply { isAccessible=true }; val previous=field.get(OfflineStore)
        val request=DownloadRequest.Builder("saved/store-delete",Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey("saved/store-delete").build()
        try {
            onWorker {first.prepare()}; val manager=first.initializeManager()
            manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads(); await(manager) {true}
            val command=first.prepareSave(request); assertTrue(first.deliver(command,request) {it.addDownload(request)})
            await(manager) { manager.downloadIndex.getDownload(request.id)?.state==Download.STATE_COMPLETED }
            val phone=Shelf.partitioned(first,MuonDownloadService::class.java); var removed:String?=null
            phone.partitionRemoved={id -> assertSame(Looper.getMainLooper(),Looper.myLooper()); removed=id}
            val app=RuntimeEnvironment.getApplication()
            val art=DownloadArt(folders.newFolder(),barrier=f.barrier);val artwork=coverWork(art,f.barrier)
            val store=OfflineStore.Store(phone,art,PlayedSongEvictor(8192L) {},
                app.getSharedPreferences("completed-removal-fixture",0),f.provider,{}, {},artwork=artwork,storageBarrier=f.barrier)
            field.set(OfflineStore,store)
            val ref=requireNotNull(SavedRef.download(SavedShelf.Phone,request.id,request.id))
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker { OfflineStore.removeSavedNow(app,ref) })
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(request.id,removed)
            assertNull(manager.downloadIndex.getDownload(request.id))
            assertEquals(PartitionSavedDeletePhase.Complete,onWorker {first.deleteRecoveryPage().single().phase})
            assertFalse(onWorker { first.audio.contains(request.id) })
            assertThrows(IOException::class.java) {onWorker { first.audio.inspect(request.id) }}
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            field.set(OfflineStore,previous); teardown(first); closed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            val nextManager=next.initializeManager(); await(nextManager) {true}
            assertNull(nextManager.downloadIndex.getDownload(request.id))
            assertFalse(onWorker { next.audio.contains(request.id) })
            assertThrows(IOException::class.java) {onWorker { next.audio.inspect(request.id) }}
            assertEquals(PartitionSavePhase.Closed,onWorker { next.saveRecoveryPage().single().phase })
            assertArrayEquals(payload,f.original.readBytes())
        } finally { field.set(OfflineStore,previous); resumed?.let(::teardown); if(!closed) teardown(first); f.provider.close() }
    }
    @Test fun explicitOwnedPlayedRemovalRefusesActiveReaderAndCannotReappearAfterRestart() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        val key=playedKey("saved/remove-owned"); val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        val field=OfflineStore::class.java.getDeclaredField("store").apply { isAccessible=true }; val previous=field.get(OfflineStore)
        try {
            onWorker {first.prepare()}; val manager=first.initializeManager(); await(manager) { true }
            onWorker { first.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",
                DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L } }
            val phone=Shelf.partitioned(first,MuonDownloadService::class.java)
            val app=RuntimeEnvironment.getApplication()
            val store=OfflineStore.Store(phone,DownloadArt(folders.newFolder()),PlayedSongEvictor(8192L) {},
                app.getSharedPreferences("played-removal-fixture",0),f.provider,{}, {},storageBarrier=f.barrier)
            field.set(OfflineStore,store)
            val ref=requireNotNull(SavedRef.played(key))
            val reader=first.audio.source.createDataSource()
            onWorker { assertEquals(4L,reader.open(spec)) }
            try {
                assertEquals(OfflineStore.SavedRemoval.Busy,onWorker { OfflineStore.removeSavedNow(app,ref) })
                onWorker {
                    val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                    assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT,reader.read(bytes,0,4))
                }
                // EOF is still an actual owned reader and cannot grant deletion.
                assertEquals(OfflineStore.SavedRemoval.Busy,onWorker { first.removePlayed(key) { true } })
            } finally { onWorker { reader.close() } }
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker { OfflineStore.removeSavedNow(app,ref) })
            assertEquals(SavedCoverage.Missing,onWorker { first.audio.inspect(key).coverage })
            assertEquals(PartitionSavePhase.Closed,onWorker { first.saveRecoveryPage().single().phase })
            field.set(OfflineStore,previous)
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertEquals(SavedCoverage.Missing,onWorker { next.audio.inspect(key).coverage })
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally { field.set(OfflineStore,previous); resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close() }
    }
    @Test fun actualAliasRowProtectsOwnedPlayedBytesWithoutHydratingItsOversizedPayload() {
        val f=Fixture(); val owner=f.owner(); val key=playedKey("saved/protected-owned")
        val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            onWorker { owner.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",
                DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L } }
            val request=DownloadRequest.Builder("saved/foreign-alias",Uri.parse("http://127.0.0.1:7814/file"))
                .setCustomCacheKey(key).build()
            val index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(f.provider,"owned_partition")
            index.putDownload(Download(request,Download.STATE_COMPLETED,1,2,4,0,0))
            f.provider.writableDatabase.execSQL("UPDATE ExoPlayerDownloadsowned_partition SET data=zeroblob(8388608)")
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { owner.removePlayed(key) { true } })
            assertEquals(SavedCoverage.Full,onWorker { owner.audio.inspect(key).coverage })
            f.provider.readableDatabase.rawQuery("SELECT length(data) FROM ExoPlayerDownloadsowned_partition",null).use {
                assertTrue(it.moveToFirst()); assertEquals(8388608L,it.getLong(0))
            }
            assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun deniedClaimAndLegacyOrPublishedOriginalsNeverReachOwnedPlayedDeletion() {
        val f=Fixture(); val owner=f.owner(); val key=playedKey("saved/denied-owned")
        val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            onWorker { owner.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",
                DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L } }
            assertEquals(0,f.budget.resident)
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { owner.removePlayed(key) { false } })
            assertEquals(0,f.budget.resident)
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { owner.removePlayed("old") { true } })
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker { owner.removePlayed(playedKey("saved/not-owned")) { true } })
            assertEquals(SavedCoverage.Full,onWorker { owner.audio.inspect(key).coverage })
            assertArrayEquals(payload,f.original.readBytes()); assertTrue(f.barrier.quiescent)
        } finally { teardown(owner); f.provider.close() }
    }

    @Test fun clearUsesBoundedJournalPagesAndReportsProtectedLegacyBytesWithoutErasingOriginals() {
        val f=Fixture(playedKey("legacy")); val owner=f.owner()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            val count=PARTITION_LOCATOR_WINDOW+3
            // This is a bounded SQLite/filesystem batch, not a latency assertion. Keep a finite
            // allowance for loaded hosted runners; the default remains short for single operations.
            onWorker(timeoutSeconds=60) {
                for(i in 0 until count) {
                    val key=playedKey("saved/clear-$i")
                    owner.copyPlayed(DataSpec.Builder().setUri("http://127.0.0.1:7814/$i").setKey(key).build(),
                        byteArrayOf(1),"http://127.0.0.1:7814/$i",DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L }
                }
                val result=owner.clearPlayed { true }
                assertEquals(count.toLong(),result.removed); assertEquals(4L*count,result.removedBytes)
                assertEquals(4L,result.remainingBytes); assertFalse(result.complete)
                assertEquals(SavedCoverage.Full,owner.audio.inspect(f.oldKey).coverage)
                val repeated=owner.clearPlayed { true }
                assertEquals(0L,repeated.removed); assertEquals(0L,repeated.removedBytes)
                assertEquals(4L,repeated.remainingBytes); assertFalse(repeated.complete)
                assertTrue(owner.saveRecoveryPage().all { it.phase==PartitionSavePhase.Closed })
                assertEquals(SavedCoverage.Missing,owner.audio.inspect(playedKey("saved/clear-0")).coverage)
            }
            assertArrayEquals(payload,f.original.readBytes()); assertTrue(f.barrier.quiescent)
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun clearRefusesAnActualReaderThenReportsEmptyOnlyAfterItCloses() {
        val f=Fixture(); val owner=f.owner(); val key=playedKey("saved/clear-reader")
        val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            onWorker { owner.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",
                DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L } }
            val reader=owner.audio.source.createDataSource()
            onWorker { reader.open(spec) }
            try {
                val busy=onWorker { owner.clearPlayed { true } }
                assertEquals(0L,busy.removed); assertEquals(4L,busy.remainingBytes); assertFalse(busy.complete)
                onWorker { val data=ByteArray(4); assertEquals(4,reader.read(data,0,4)); assertArrayEquals(payload,data) }
            } finally { onWorker { reader.close() } }
            val cleared=onWorker { owner.clearPlayed { true } }
            assertEquals(1L,cleared.removed); assertEquals(4L,cleared.removedBytes)
            assertEquals(0L,cleared.remainingBytes); assertTrue(cleared.complete)
            assertArrayEquals(payload,f.original.readBytes()); assertTrue(f.barrier.quiescent)
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun clearSkipsAliasAndCallerClaimsAndCountsTheirActualRemainingBytes() {
        val f=Fixture(); val owner=f.owner()
        val alias=playedKey("saved/clear-alias"); val denied=playedKey("saved/clear-denied"); val eligible=playedKey("saved/clear-free")
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            onWorker {
                for(key in listOf(alias,denied,eligible)) owner.copyPlayed(
                    DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build(),byteArrayOf(1),
                    "http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 8192L }
            }
            val request=DownloadRequest.Builder("saved/clear-foreign",Uri.parse("http://127.0.0.1:7814/file"))
                .setCustomCacheKey(alias).build()
            androidx.media3.exoplayer.offline.DefaultDownloadIndex(f.provider,"owned_partition")
                .putDownload(Download(request,Download.STATE_COMPLETED,1,2,4,0,0))
            f.provider.writableDatabase.execSQL("UPDATE ExoPlayerDownloadsowned_partition SET data=zeroblob(8388608)")
            val result=onWorker { owner.clearPlayed { it!=denied } }
            assertEquals(1L,result.removed); assertEquals(4L,result.removedBytes)
            assertEquals(8L,result.remainingBytes); assertFalse(result.complete)
            onWorker {
                assertEquals(SavedCoverage.Full,owner.audio.inspect(alias).coverage)
                assertEquals(SavedCoverage.Full,owner.audio.inspect(denied).coverage)
                assertEquals(SavedCoverage.Missing,owner.audio.inspect(eligible).coverage)
            }
            f.provider.readableDatabase.rawQuery("SELECT length(data) FROM ExoPlayerDownloadsowned_partition",null).use {
                assertTrue(it.moveToFirst()); assertEquals(8388608L,it.getLong(0))
            }
            assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }

    @Test fun aggregatePlayedBudgetIncludesProtectedLegacyBytesAndSurvivesOwnerRestart() {
        val f=Fixture(playedKey("legacy")); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        val key=playedKey("saved/aggregate-first"); val denied=playedKey("saved/aggregate-denied")
        fun spec(name:String)=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(name).build()
        try {
            onWorker {first.prepare()}; val manager=first.initializeManager(); await(manager) { true }
            onWorker {
                first.copyPlayed(spec(key),byteArrayOf(1),"http://127.0.0.1:7814/1",
                    DataSource.Factory { ByteArrayDataSource(payload) }) { 8L }
                assertEquals(8L,first.playedBytes())
                assertThrows(PlayedCopyTooLarge::class.java) { first.copyPlayed(spec(denied),byteArrayOf(1),
                    "http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 8L } }
                assertTrue(first.saveRecoveryPage().none { it.ticket.allocation.key==denied })
            }
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            onWorker {
                assertEquals(8L,next.playedBytes())
                assertThrows(PlayedCopyTooLarge::class.java) { next.copyPlayed(spec(denied),byteArrayOf(1),
                    "http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 4L } }
                assertEquals(SavedCoverage.Full,next.audio.inspect(f.oldKey).coverage)
                assertEquals(SavedCoverage.Full,next.audio.inspect(key).coverage)
                assertTrue(next.saveRecoveryPage().none { it.ticket.allocation.key==denied })
            }
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally { resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close() }
    }
    @Test fun failedHeadroomCopyCleansOnlyItsFreshBytesAndReleasesItsAdmission() {
        val f=Fixture(); val owner=f.owner()
        val first=playedKey("saved/headroom-first"); val tooLarge=playedKey("saved/headroom-large"); val last=playedKey("saved/headroom-last")
        fun spec(key:String)=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            onWorker {
                owner.copyPlayed(spec(first),byteArrayOf(1),"http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 6L }
                assertThrows(PlayedCopyTooLarge::class.java) { owner.copyPlayed(spec(tooLarge),byteArrayOf(1),
                    "http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 6L } }
                assertEquals(4L,owner.playedBytes()); assertEquals(SavedCoverage.Full,owner.audio.inspect(first).coverage)
                assertEquals(SavedCoverage.Missing,owner.audio.inspect(tooLarge).coverage)
                owner.copyPlayed(spec(last),byteArrayOf(1),"http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 8L }
                assertEquals(8L,owner.playedBytes())
            }
            assertArrayEquals(payload,f.original.readBytes()); assertTrue(f.barrier.quiescent)
        } finally { teardown(owner); f.provider.close() }
    }
    @Test fun concurrentPlayedCopiesCannotBothReserveTheSameAggregateHeadroom() {
        val f=Fixture(); val owner=f.owner(); val entered=java.util.concurrent.CountDownLatch(1); val release=java.util.concurrent.CountDownLatch(1)
        val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
        val first=playedKey("saved/concurrent-first"); val second=playedKey("saved/concurrent-second")
        fun spec(key:String)=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            val raw=ByteArrayDataSource(payload)
            val blocked=DataSource.Factory { object:DataSource by raw {
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    entered.countDown(); check(release.await(10,TimeUnit.SECONDS)); return raw.read(buffer,offset,length)
                }
            } }
            val task=worker.submit { owner.copyPlayed(spec(first),byteArrayOf(1),"http://127.0.0.1:7814/1",blocked) { 4L } }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { onWorker { owner.copyPlayed(spec(second),byteArrayOf(1),
                "http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 4L } } }
            assertTrue(onWorker { owner.saveRecoveryPage() }.none { it.ticket.allocation.key==second })
            release.countDown(); task.get(10,TimeUnit.SECONDS)
            assertEquals(4L,onWorker { owner.playedBytes() }); assertArrayEquals(payload,f.original.readBytes())
        } finally { release.countDown(); worker.shutdownNow(); assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS)); teardown(owner); f.provider.close() }
    }
    @Test fun cancelledPlayedCensusRefusesBeforeReservationAndAllowsALaterHealthyCopy() {
        val f=Fixture(); val owner=f.owner(); val key=playedKey("saved/cancelled-census")
        val spec=DataSpec.Builder().setUri("http://127.0.0.1:7814/file").setKey(key).build()
        try {
            onWorker {owner.prepare()}; val manager=owner.initializeManager(); await(manager) { true }
            onWorker {
                assertThrows(java.io.InterruptedIOException::class.java) {
                    owner.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) },
                        checkpoint={ throw java.io.InterruptedIOException("Cancelled test census") }) { 4L }
                }
                assertTrue(owner.saveRecoveryPage().isEmpty()); assertEquals(0,f.budget.resident)
                owner.copyPlayed(spec,byteArrayOf(1),"http://127.0.0.1:7814/1",DataSource.Factory { ByteArrayDataSource(payload) }) { 4L }
                assertEquals(4L,owner.playedBytes())
            }
            assertTrue(f.barrier.quiescent); assertArrayEquals(payload,f.original.readBytes())
        } finally { teardown(owner); f.provider.close() }
    }

    @Test fun originalIntentRequiresConsentAndRefusesReaderEvenAfterEof() {
        val f=Fixture(); val owner=f.owner()
        try {
            onWorker {owner.prepare()}
            assertThrows(IOException::class.java) {onWorker {owner.beginOriginalRetirement("old",false)}}
            assertTrue(owner.audio.contains("old")); assertTrue(onWorker {owner.retirementRecoveryPage()}.isEmpty())
            val reader=owner.audio.source.createDataSource()
            try {
                assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey("old").build()))
                assertEquals(4,reader.read(ByteArray(4),0,4)); assertEquals(-1,reader.read(ByteArray(1),0,1))
                assertThrows(IOException::class.java) {onWorker {owner.beginOriginalRetirement("old",true)}}
                assertTrue(onWorker {owner.retirementRecoveryPage()}.isEmpty())
            } finally {reader.close()}
            val pending=onWorker {owner.beginOriginalRetirement("old",true)}
            assertEquals(LegacyRetirementPhase.Pending,pending.phase)
            assertEquals(listOf(pending),onWorker {owner.retirementRecoveryPage()})
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertFalse(owner.audio.contains("old")); assertTrue(owner.claimsName("old"))
            assertThrows(IOException::class.java) {owner.audio.inspect("old")}
            val hidden=owner.audio.source.createDataSource()
            try {assertThrows(IOException::class.java) {hidden.open(DataSpec.Builder().setUri("muon-saved:test").setKey("old").build())}}
            finally {hidden.close()}
            val keys=mutableListOf<String>(); owner.audio.forEachKey {keys.add(it); true}; assertFalse(keys.contains("old"))
            assertThrows(IOException::class.java) {onWorker {owner.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})}}
            assertThrows(IOException::class.java) {onWorker {owner.beginOriginalRetirement("old",true)}}
            assertEquals(0,f.budget.resident)
        } finally {teardown(owner); f.provider.close()}
    }
    @Test fun pendingOriginalIntentSuppressesReadyRouteAndSurvivesActualShelfRestart() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        try {
            onWorker {first.prepare()}
            val ready=first.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})
            assertEquals(MigrationPhase.Ready,ready.phase); assertTrue(first.audio.contains("old"))
            val pending=onWorker {first.beginOriginalRetirement("old",true)}
            assertFalse(first.audio.contains("old")); assertArrayEquals(payload,f.original.readBytes())
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertEquals(listOf(pending),onWorker {next.retirementRecoveryPage()})
            assertFalse(next.audio.contains("old")); assertTrue(next.claimsName("old"))
            assertThrows(IOException::class.java) {onWorker {next.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})}}
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally {resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close()}
    }
    @Test fun replacedRetirementJournalStopsRoutingWithoutAdoptingReplacement() {
        val f=Fixture(); val owner=f.owner()
        try {
            onWorker {owner.prepare()}
            val file=File(f.root,"legacy-retirements-v1.db"); val kept=File(f.root,"retained-retirements.db")
            assertTrue(file.renameTo(kept)); kept.copyTo(file)
            assertFalse(owner.isAvailable)
            assertThrows(IOException::class.java) {owner.audio.contains("old")}
            assertThrows(IOException::class.java) {onWorker {owner.beginOriginalRetirement("old",true)}}
            assertArrayEquals(payload,f.original.readBytes()); assertTrue(kept.isFile)
        } finally {teardown(owner); f.provider.close()}
    }
    @Test fun preparationRefusesMainThreadBeforeFilesystemOrOwnerConstruction() {
        val f=Fixture(); val owner=f.owner()
        try {
            assertThrows(IllegalStateException::class.java) {owner.prepare()}
            assertFalse(f.root.exists()); assertArrayEquals(payload,f.original.readBytes())
            onWorker {owner.prepare()}; assertTrue(owner.isAvailable)
        } finally {teardown(owner); f.provider.close()}
    }

    @Test fun actualOriginalFilesStageNeedsExactConsentAndKeepsPendingUntilFinalDisposition() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        try {
            onWorker {first.prepare()}; val pending=onWorker {first.beginOriginalRetirement("old",true)}
            assertThrows(IOException::class.java) {onWorker {first.retireOriginalFiles(pending,false)}}
            assertThrows(IOException::class.java) {onWorker {first.retireOriginalFiles(pending.copy(token=java.util.UUID.randomUUID().toString()),true)}}
            assertArrayEquals(payload,f.original.readBytes())
            assertEquals(LegacyRetirementFileResult(4,0),onWorker {first.retireOriginalFiles(pending,true)})
            assertFalse(f.original.exists()); assertEquals(listOf(pending),onWorker {first.retirementRecoveryPage()})
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertFalse(next.audio.contains("old")); assertEquals(listOf(pending),onWorker {next.retirementRecoveryPage()})
            assertEquals(LegacyRetirementFileResult(0,4),onWorker {next.retireOriginalFiles(pending,true)})
            assertTrue(next.claimsName("old")); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally {resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close()}
    }

    @Test fun originalCleanupAlsoRetiresReadyDuplicateAndDoesNotResurrectItOnRestart() {
        val f=Fixture(); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        try {
            onWorker {first.prepare()}
            val ready=first.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})
            val target=File(File(f.root,"resources"),ready.ticket.allocation.directory)
            assertTrue(target.walkTopDown().any {it.isFile && it.name.endsWith(".exo")})
            assertTrue(first.audio.contains("old"))
            val pending=onWorker {first.beginOriginalRetirement("old",true)}
            assertThrows(IOException::class.java) {onWorker {first.retireOriginalFiles(pending,false)}}
            assertTrue(f.original.exists()); assertTrue(target.walkTopDown().any {it.isFile && it.name.endsWith(".exo")})
            assertEquals(LegacyRetirementFileResult(4,0),onWorker {first.retireOriginalFiles(pending,true)})
            assertFalse(f.original.exists()); assertFalse(target.walkTopDown().any {it.isFile && it.name.endsWith(".exo")})
            assertEquals(0,f.budget.resident); assertFalse(first.audio.contains("old"))
            CacheMigrationJournal(f.root).use {assertEquals(ready,it.ready("old"))}
            assertEquals(listOf(pending),onWorker {first.retirementRecoveryPage()})
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertFalse(next.audio.contains("old")); assertTrue(next.claimsName("old"))
            assertEquals(LegacyRetirementFileResult(0,4),onWorker {next.retireOriginalFiles(pending,true)})
            assertFalse(target.walkTopDown().any {it.isFile && it.name.endsWith(".exo")})
            assertEquals(0,f.budget.resident)
        } finally {resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close()}
    }

    @Test fun originalDuplicateCleanupRejectsAnExclusivePermitFromAnotherGate() {
        val f=Fixture(); val owner=f.owner()
        try {
            onWorker {owner.prepare()}
            owner.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})
            val pending=onWorker {owner.beginOriginalRetirement("old",true)}
            val startup=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply {isAccessible=true}.get(owner)
            val made=StorageStartup::class.java.getDeclaredField("opened").apply {isAccessible=true}.get(startup)
            val session=made.javaClass.getDeclaredField("session").apply {isAccessible=true}.get(made) as PartitionStorageSession
            val foreign=SavedStorageBarrier().exclusive()
            try {assertThrows(IOException::class.java) {onWorker {session.retireMigratedOriginal(pending,foreign)}}}
            finally {foreign.close()}
            assertArrayEquals(payload,f.original.readBytes()); assertEquals(0,f.budget.resident)
            assertEquals(LegacyRetirementFileResult(4,0),onWorker {owner.retireOriginalFiles(pending,true)})
        } finally {teardown(owner); f.provider.close()}
    }

    private fun seedOriginal(f:Fixture):DefaultDownloadIndex {
        val request=DownloadRequest.Builder(f.oldKey,Uri.parse("http://127.0.0.1:7814/api1/fileopus/42"))
            .setCustomCacheKey(f.oldKey).setData(byteArrayOf(7)).build()
        return DefaultDownloadIndex(f.provider,"owned_partition").also {
            it.putDownload(Download(request,Download.STATE_COMPLETED,1,2,4,0,0))
        }
    }
    private fun fixtureRetirements(owner:OwnedPartitionShelf):LegacyRetirementJournal {
        val startup=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply {isAccessible=true}.get(owner)
        val made=StorageStartup::class.java.getDeclaredField("opened").apply {isAccessible=true}.get(startup)
        return made.javaClass.getDeclaredField("retirements").apply {isAccessible=true}.get(made) as LegacyRetirementJournal
    }
    private fun boundOriginalIntent(f:Fixture,owner:OwnedPartitionShelf):LegacyRetirementRecord=onWorker {
        val permit=f.barrier.exclusive()
        try {
            android.database.sqlite.SQLiteDatabase.openDatabase(f.databaseFile.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db ->
                val uid=java.lang.Long.parseLong(f.legacy.listFiles()!!.single {it.name.endsWith(".uid")}.name.removeSuffix(".uid"),16)
                val completion=requireNotNull(PartitionCompletionIndex(f.provider,"owned_partition").findOriginal(f.oldKey))
                val manifest=LegacyRetirementManifest.capture(f.legacy,uid,f.oldKey,db,{}).withCompletedDownload(completion)
                fixtureRetirements(owner).begin(manifest,true)
            }
        } finally {permit.close()}
    }
    @Test fun completeOriginalDeleteDisposesReadyBytesTerminalRecordAndCoverBeforeComplete() {
        val f=Fixture("42"); val index=seedOriginal(f); val owner=f.owner(); var covers=0
        try {
            onWorker {owner.prepare()}
            owner.migrate(CacheMigrationControl(1000,nanoTime={0L}),f.oldKey,{Long.MAX_VALUE},{})
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {owner.removeOriginalCompleted(f.oldKey,false,{true},{_,_ ->covers++})})
            assertArrayEquals(payload,f.original.readBytes()); assertNotNull(index.getDownload(f.oldKey))
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {owner.removeOriginalCompleted(f.oldKey,true,{true},{_,permit ->
                assertTrue(permit.exclusive); assertTrue(permit.belongsTo(f.barrier)); assertNull(index.getDownload(f.oldKey)); covers++
            })})
            assertEquals(1,covers); assertFalse(f.original.exists()); assertNull(index.getDownload(f.oldKey))
            val complete=onWorker {owner.retirementRecoveryPage()}.single()
            assertEquals(LegacyRetirementPhase.Complete,complete.phase); assertEquals(64,complete.manifest.completedDownloadIdentity?.length)
            assertTrue(owner.claimsName(f.oldKey)); assertFalse(owner.audio.contains(f.oldKey)); assertEquals(0,f.budget.resident)
        } finally {teardown(owner); f.provider.close()}
    }
    @Test fun exactBoundPendingRecoveryFinishesAfterRestartWithAlreadyRemovedBytesAndRecord() {
        val f=Fixture("42"); val index=seedOriginal(f); val first=f.owner(); var firstClosed=false; var resumed:OwnedPartitionShelf?=null
        try {
            onWorker {first.prepare()}; val pending=boundOriginalIntent(f,first)
            onWorker {first.retireOriginalFiles(pending,true)}
            val permit=f.barrier.exclusive(); try {index.removeDownload(f.oldKey)} finally {permit.close()}
            teardown(first); firstClosed=true
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}; var covers=0
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {next.removeOriginalCompleted(f.oldKey,true,{true},{_,_ ->covers++})})
            assertEquals(0,covers)
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {next.removeOriginalCompleted(f.oldKey,true,{true},{_,_ ->covers++},pending)})
            assertEquals(1,covers); assertNull(index.getDownload(f.oldKey)); assertFalse(f.original.exists())
            assertEquals(LegacyRetirementPhase.Complete,onWorker {next.retirementRecoveryPage()}.single().phase)
        } finally {resumed?.let(::teardown); if(!firstClosed) teardown(first); f.provider.close()}
    }
    @Test fun changedCompletedIdentityAndFilesOnlyIntentCannotRemoveTerminalRecord() {
        val f=Fixture("42"); val index=seedOriginal(f); val owner=f.owner(); var covers=0
        try {
            onWorker {owner.prepare()}; val pending=boundOriginalIntent(f,owner)
            val request=requireNotNull(index.getDownload(f.oldKey)).request
            index.putDownload(Download(request,Download.STATE_COMPLETED,1,9,4,0,0))
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {owner.removeOriginalCompleted(f.oldKey,true,{true},{_,_ ->covers++},pending)})
            assertEquals(0,covers); assertArrayEquals(payload,f.original.readBytes()); assertEquals(9L,index.getDownload(f.oldKey)?.updateTimeMs)
            assertEquals(pending,onWorker {owner.retirementRecoveryPage()}.single())
        } finally {teardown(owner); f.provider.close()}
        val g=Fixture("43"); val otherIndex=seedOriginal(g); val other=g.owner()
        try {
            onWorker {other.prepare()}; val filesOnly=onWorker {other.beginOriginalRetirement(g.oldKey,true)}
            assertNull(filesOnly.manifest.completedDownloadIdentity)
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {other.removeOriginalCompleted(g.oldKey,true,{true},{_,_ ->covers++},filesOnly)})
            assertArrayEquals(payload,g.original.readBytes()); assertNotNull(otherIndex.getDownload(g.oldKey)); assertEquals(0,covers)
        } finally {teardown(other); g.provider.close()}
    }
    @Test fun originalDeleteRefusesCrossShelfAndLocalAliasesBeforeIntentOrBytes() {
        val f=Fixture("42"); val index=seedOriginal(f); val owner=f.owner()
        try {
            onWorker {owner.prepare()}
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {owner.removeOriginalCompleted(f.oldKey,true,{false},{_,_ ->fail("No cover mutation")})})
            val alias=DownloadRequest.Builder("other",Uri.parse("http://127.0.0.1:7814/api1/fileopus/42")).setCustomCacheKey(f.oldKey).build()
            index.putDownload(Download(alias,Download.STATE_STOPPED,1,2,4,1,0))
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {owner.removeOriginalCompleted(f.oldKey,true,{true},{_,_ ->fail("No cover mutation")})})
            assertTrue(onWorker {owner.retirementRecoveryPage()}.isEmpty()); assertArrayEquals(payload,f.original.readBytes())
            assertNotNull(index.getDownload(f.oldKey)); assertNotNull(index.getDownload("other"))
        } finally {teardown(owner); f.provider.close()}
    }

    @Test fun actualOriginalLocalCopyVerifiesDestinationAndPreservesOriginalRecordAndBytes() {
        val f=Fixture(oldKey="41"); val source=f.owner(); val targetLegacy=folders.newFolder()
        val oldTarget=SimpleCache(targetLegacy,NoOpCacheEvictor(),f.provider)
        oldTarget.checkInitialization(); oldTarget.release()
        val targetRoot=File(folders.newFolder(),"target-partition")
        val target=OwnedPartitionShelf(RuntimeEnvironment.getApplication(),targetRoot,targetLegacy,f.databaseFile,
            f.provider,"owned_target","fixture",{"fixture"},PartitionShelfStart.Fresh,f.barrier,{true},
            DataSource.Factory {throw AssertionError("Local Move must not request live upstream")},f.budget)
        val request=DownloadRequest.Builder("41",Uri.parse("http://127.0.0.1:7814/original")).setCustomCacheKey("41").build()
        val originalIndex=DefaultDownloadIndex(f.provider,"owned_partition")
        originalIndex.putDownload(Download(request,Download.STATE_COMPLETED,1,2,4,0,0))
        try {
            onWorker {source.prepare(); target.prepare()}
            await(source.initializeManager()) {true}; await(target.initializeManager()) {true}
            val receipt=onWorker {source.copyCompletedOriginalTo(target,"41","saved/local-move",true)}
            assertEquals("41",receipt.source.key); assertEquals(4L,receipt.source.length)
            assertEquals(PartitionSavePhase.Closed,onWorker {target.saveRecoveryPage().single().phase})
            assertEquals(receipt.destination,onWorker {target.saveRecoveryPage().single().ticket})
            // No destination manager completion, source tombstone or removal is invented.
            assertFalse(onWorker {target.audio.contains("saved/local-move")})
            assertTrue(onWorker {source.audio.contains("41")})
            assertTrue(onWorker {source.retirementRecoveryPage().isEmpty()})
            assertNotNull(originalIndex.getDownload("41")); assertArrayEquals(payload,f.original.readBytes())
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy)); assertEquals(0,f.budget.resident); assertTrue(f.barrier.quiescent)
            assertThrows(IOException::class.java) {onWorker {source.copyCompletedOriginalTo(target,"41","saved/local-move",true)}}
            assertArrayEquals(payload,f.original.readBytes())
        } finally {teardown(target); teardown(source); f.provider.close()}
    }
    @Test fun actualOriginalLocalCopyRefusesNoConsentForeignGateAndChangedCompletionBeforeMutation() {
        val f=Fixture(oldKey="42"); val source=f.owner(); val targetLegacy=folders.newFolder()
        val oldTarget=SimpleCache(targetLegacy,NoOpCacheEvictor(),f.provider); oldTarget.checkInitialization(); oldTarget.release()
        val targetRoot=File(folders.newFolder(),"copy-target"); val foreignRoot=File(folders.newFolder(),"foreign-target")
        fun target(root:File,gate:SavedStorageBarrier)=OwnedPartitionShelf(RuntimeEnvironment.getApplication(),root,
            targetLegacy,f.databaseFile,f.provider,"copy_target","fixture",{"fixture"},PartitionShelfStart.Fresh,
            gate,{true},DataSource.Factory {ByteArrayDataSource(payload)},f.budget)
        val target=target(targetRoot,f.barrier); val foreign=target(foreignRoot,SavedStorageBarrier())
        val request=DownloadRequest.Builder("42",Uri.parse("http://127.0.0.1:7814/original")).setCustomCacheKey("42").build()
        val index=DefaultDownloadIndex(f.provider,"owned_partition")
        index.putDownload(Download(request,Download.STATE_COMPLETED,1,2,5,0,0))
        try {
            onWorker {source.prepare(); target.prepare(); foreign.prepare()}
            await(source.initializeManager()) {true}; await(target.initializeManager()) {true}
            assertThrows(IOException::class.java) {onWorker {source.copyCompletedOriginalTo(target,"42","saved/no-consent",false)}}
            assertThrows(IOException::class.java) {onWorker {source.copyCompletedOriginalTo(foreign,"42","saved/foreign",true)}}
            assertThrows(IOException::class.java) {onWorker {source.copyCompletedOriginalTo(target,"42","saved/length-mismatch",true)}}
            assertTrue(onWorker {target.saveRecoveryPage().isEmpty()}); assertTrue(onWorker {foreign.saveRecoveryPage().isEmpty()})
            assertArrayEquals(payload,f.original.readBytes()); assertTrue(f.barrier.quiescent); assertEquals(0,f.budget.resident)
        } finally {teardown(foreign); teardown(target); teardown(source); f.provider.close()}
    }

    private inner class LocalPublicationFixture(val key:String="saved/published-local-copy") {
        val f=Fixture(oldKey="81"); val source=f.owner(); val targetLegacy=folders.newFolder()
        val targetRoot=File(folders.newFolder(),"publication-target")
        val request=DownloadRequest.Builder("81",Uri.parse("http://127.0.0.1:7814/original"))
            .setCustomCacheKey("81").setData(byteArrayOf(7,8,9)).build()
        val index=DefaultDownloadIndex(f.provider,"owned_partition")
        var target:OwnedPartitionShelf
        var sourceClosed=false; var targetClosed=false
        var targetVolume:String?="fixture"
        init {
            val cache=SimpleCache(targetLegacy,NoOpCacheEvictor(),f.provider); cache.checkInitialization(); cache.release()
            index.putDownload(Download(request,Download.STATE_COMPLETED,1,2,4,0,0))
            target=target()
            onWorker {source.prepare(); target.prepare()}
            await(source.initializeManager()) {true}; await(target.initializeManager()) {true}
        }
        fun target(start:PartitionShelfStart=PartitionShelfStart.Fresh)=OwnedPartitionShelf(
            RuntimeEnvironment.getApplication(),targetRoot,targetLegacy,f.databaseFile,f.provider,"publication_target",
            "fixture",{targetVolume},start,f.barrier,{true},
            DataSource.Factory {throw AssertionError("Local publication must never stream live upstream")},f.budget)
        fun prepare()=onWorker {source.copyCompletedOriginalTo(target,"81",key,true)}
        fun close() {
            if(!targetClosed) teardown(target)
            if(!sourceClosed) teardown(source)
            f.provider.close()
        }
    }
    @Test fun actualLocalPublicationRecomparesWritesExactCompletionAndReadsAfterRestart() {
        val f=LocalPublicationFixture()
        try {
            val copy=f.prepare()
            val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            assertEquals(f.key,completed.key); assertEquals(4L,completed.length)
            assertEquals(10L,completed.started); assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            val row=f.target.initializeManager().downloadIndex.getDownload(f.key)!!
            assertEquals(Download.STATE_COMPLETED,row.state); assertEquals(4L,row.bytesDownloaded)
            assertEquals(100f,row.percentDownloaded,0f); assertArrayEquals(f.request.data,row.request.data)
            assertEquals(f.request.uri,row.request.uri); assertEquals(f.key,row.request.customCacheKey)
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            onWorker {
                val source=f.target.audio.source.createDataSource()
                try {
                    assertEquals(4L,source.open(DataSpec.Builder().setUri("muon-saved:test").setKey(f.key).build()))
                    val bytes=ByteArray(4); assertEquals(4,source.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                } finally {source.close()}
            }
            teardown(f.target); f.targetClosed=true
            val resumed=f.target(PartitionShelfStart.Resume); f.target=resumed; f.targetClosed=false
            onWorker {resumed.prepare()}; await(resumed.initializeManager()) {true}
            assertEquals(SavedCoverage.Full,onWorker {resumed.audio.inspect(f.key).coverage})
            assertEquals(completed,PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
        } finally {f.close()}
    }
    @Test fun localPublicationRefusesNoConsentActualEofReaderAndForeignNameClaim() {
        val f=LocalPublicationFixture()
        try {
            val copy=f.prepare()
            assertThrows(IOException::class.java) {onWorker {f.source.publishCopiedOriginalTo(f.target,copy,false,{true},10)}}
            val reader=f.source.audio.source.createDataSource()
            try {
                onWorker {
                    reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey("81").build())
                    assertEquals(4,reader.read(ByteArray(4),0,4)); assertEquals(-1,reader.read(ByteArray(1),0,1))
                }
                assertThrows(IOException::class.java) {onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}}
            } finally {onWorker {reader.close()}}
            assertThrows(IOException::class.java) {onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{false},10)}}
            assertNull(f.target.initializeManager().downloadIndex.getDownload(f.key))
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertEquals(0,f.f.budget.resident); assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun localPublicationDetectsChangedDestinationBytesAndPreservesBothRecords() {
        val f=LocalPublicationFixture()
        try {
            val copy=f.prepare()
            val bytes=f.targetRoot.walkTopDown().filter {it.isFile && it.name.endsWith(".v3.exo")}.toList().single()
            bytes.writeBytes(byteArrayOf(9,9,9,9))
            assertThrows(IOException::class.java) {onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}}
            assertNull(f.target.initializeManager().downloadIndex.getDownload(f.key))
            assertNotNull(f.index.getDownload("81")); assertArrayEquals(payload,f.f.original.readBytes())
            assertArrayEquals(byteArrayOf(9,9,9,9),bytes.readBytes())
            assertEquals(0,f.f.budget.resident); assertTrue(f.f.barrier.quiescent)
            assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
        } finally {f.close()}
    }
    @Test fun localPublicationDoesNotOverwriteExistingCompletedDestination() {
        val f=LocalPublicationFixture()
        try {
            val copy=f.prepare(); val expected=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            assertThrows(IOException::class.java) {onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},20)}}
            assertEquals(expected,PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertNotNull(f.index.getDownload("81")); assertArrayEquals(payload,f.f.original.readBytes())
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }

    @Test fun durableMoveIntentBindsActualDestinationAndGenericDeletionCannotBypassItAfterRestart() {
        val f=LocalPublicationFixture(); var resumed:OwnedPartitionShelf?=null
        try {
            val copy=f.prepare(); val complete=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCopiedOriginalMove(f.target,copy,complete,true)}
            assertEquals(LegacyRetirementPhase.Pending,intent.phase)
            val target=intent.manifest.moveDestination!!
            target.validate(); assertEquals(copy.destination,target.ticket); assertEquals(complete.retirementIdentity(),target.completionIdentity)
            assertEquals(4L,target.length); assertEquals(intent.manifest,LegacyRetirementManifest.decode(intent.manifest.encode()))
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {
                f.source.removeOriginalCompleted("81",true,{true},{_,_ -> throw AssertionError("Move cannot use generic Delete")},intent)
            })
            assertThrows(IOException::class.java) {onWorker {f.source.retireOriginalFiles(intent,true)}}
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertEquals(SavedCoverage.Full,onWorker {f.target.audio.inspect(f.key).coverage})
            teardown(f.source); f.sourceClosed=true
            val next=f.f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}; await(next.initializeManager()) {true}
            assertEquals(intent,onWorker {next.retirementRecoveryPage().single()})
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {
                next.removeOriginalCompleted("81",true,{true},{_,_ -> throw AssertionError("Move recovery must verify destination")},intent)
            })
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
        } finally {resumed?.let(::teardown); f.close()}
    }
    @Test fun MoveIntentRefusesChangedDestinationBytesBeforePersistingIntentOrRemovingSource() {
        val f=LocalPublicationFixture()
        try {
            val copy=f.prepare(); val complete=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            assertThrows(IOException::class.java) {onWorker {f.source.beginCopiedOriginalMove(f.target,copy,complete,false)}}
            val bytes=f.targetRoot.walkTopDown().filter {it.isFile && it.name.endsWith(".v3.exo")}.toList().single()
            bytes.writeBytes(byteArrayOf(9,9,9,9))
            assertThrows(IOException::class.java) {onWorker {f.source.beginCopiedOriginalMove(f.target,copy,complete,true)}}
            assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertEquals(0,f.f.budget.resident); assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }

    private fun coverFile(art:DownloadArt,id:String):File=DownloadArt::class.java
        .getDeclaredMethod("entryFile",String::class.java).apply {isAccessible=true}.invoke(art,id) as File
    private fun coverWork(art:DownloadArt,gate:SavedStorageBarrier)=DownloadArtworkWork(
        java.util.concurrent.Executor {throw AssertionError("Covered Move must not fetch live artwork")},
        {_,_ ->throw AssertionError("Covered Move must not fetch live artwork")},art::removeEntry,barrier=gate)
    @Test fun coveredMovePersistsPresentCoverBeforeAnySourceRemovalAndReadsAfterRestart() {
        val f=LocalPublicationFixture(); var resumed:OwnedPartitionShelf?=null
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        val bytes=byteArrayOf(8,6,4,2); val original=coverFile(art,"81").apply {writeBytes(bytes)}
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            assertEquals(4L,intent.manifest.moveCover!!.length)
            assertEquals(intent.manifest,LegacyRetirementManifest.decode(intent.manifest.encode()))
            assertArrayEquals(bytes,original.readBytes()); assertArrayEquals(bytes,coverFile(art,f.key).readBytes())
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            teardown(f.source); f.sourceClosed=true
            val next=f.f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertEquals(intent,onWorker {next.retirementRecoveryPage().single()})
        } finally {resumed?.let(::teardown); f.close()}
    }
    @Test fun explicitCoverUpgradePreservesExactPendingTokenAndRefusesRepeatedOrUnconsentedUpgrade() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val old=onWorker {f.source.beginCopiedOriginalMove(f.target,copy,completed,true)}
            // Actual v3 payload compatibility: remove only the absent v4 cover tail.
            val v3=old.manifest.encode().dropLast(1).toByteArray().also {it[3]=3}
            assertEquals(old.manifest,LegacyRetirementManifest.decode(v3))
            assertThrows(IOException::class.java) {onWorker {
                f.source.beginCoveredOriginalMove(f.target,copy,completed,false,art,work,old)
            }}
            assertEquals(old,onWorker {f.source.retirementRecoveryPage().single()})
            val bound=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work,old)}
            assertEquals(old.token,bound.token); assertEquals(SavedMoveCoverIdentity(0,null),bound.manifest.moveCover)
            assertThrows(IOException::class.java) {onWorker {
                f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work,old)
            }}
            assertEquals(bound,onWorker {f.source.retirementRecoveryPage().single()})
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
        } finally {f.close()}
    }
    @Test fun missingOriginalCannotUpgradeCoverProofOnAnOlderPendingMove() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val old=onWorker {f.source.beginCopiedOriginalMove(f.target,copy,completed,true)}
            assertTrue(f.f.original.delete())
            assertThrows(IOException::class.java) {onWorker {
                f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work,old)
            }}
            assertEquals(old,onWorker {f.source.retirementRecoveryPage().single()}); assertNotNull(f.index.getDownload("81"))
            assertFalse(coverFile(art,f.key).exists())
        } finally {f.close()}
    }
    @Test fun differentTargetCoverOrForeignCoverGateRefusesBeforeMoveIntent() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            coverFile(art,"81").writeBytes(byteArrayOf(1)); coverFile(art,f.key).writeBytes(byteArrayOf(2))
            assertThrows(IOException::class.java) {onWorker {
                f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)
            }}
            val foreign=DownloadArt(folders.newFolder(),barrier=SavedStorageBarrier())
            assertThrows(IOException::class.java) {onWorker {
                f.source.beginCoveredOriginalMove(f.target,copy,completed,true,foreign,work)
            }}
            assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            assertArrayEquals(byteArrayOf(1),coverFile(art,"81").readBytes())
            assertArrayEquals(byteArrayOf(2),coverFile(art,f.key).readBytes())
            assertArrayEquals(payload,f.f.original.readBytes())
        } finally {f.close()}
    }

    @Test fun actualCoveredMoveCompletesOnlyAfterDestinationProofAndKeepsTargetReadableAfterRestart() {
        val f=LocalPublicationFixture(); var resumedSource:OwnedPartitionShelf?=null; var resumedTarget:OwnedPartitionShelf?=null
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        val cover=byteArrayOf(1,3,5); coverFile(art,"81").writeBytes(cover)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertFalse(f.f.original.exists()); assertNull(f.index.getDownload("81")); assertFalse(coverFile(art,"81").exists())
            assertArrayEquals(cover,coverFile(art,f.key).readBytes()); assertNotNull(f.target.initializeManager().downloadIndex.getDownload(f.key))
            assertEquals(LegacyRetirementPhase.Complete,onWorker {f.source.retirementRecoveryPage().single().phase})
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertEquals(0,f.f.budget.resident) // Destination verification releases its actual native cache.
            teardown(f.source); f.sourceClosed=true; teardown(f.target); f.targetClosed=true
            val source=f.f.owner(PartitionShelfStart.Resume); resumedSource=source
            val target=f.target(PartitionShelfStart.Resume); resumedTarget=target
            onWorker {source.prepare(); target.prepare()}
            assertEquals(LegacyRetirementPhase.Complete,onWorker {source.retirementRecoveryPage().single().phase})
            onWorker {
                val reader=target.audio.source.createDataSource()
                try {
                    assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(f.key).build()))
                    val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                } finally {reader.close()}
            }
            assertEquals(1,f.f.budget.resident) // Playback retains one known idle cache in its bounded reader pool.
            assertTrue(f.f.barrier.quiescent)
        } finally {resumedTarget?.let(::teardown); resumedSource?.let(::teardown); f.close(); assertEquals(0,f.f.budget.resident)}
    }
    @Test fun changedTargetAudioRefusesMoveCleanupAndPreservesOriginalRowBytesAndCover() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); coverFile(art,"81").writeBytes(byteArrayOf(5))
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            f.targetRoot.walkTopDown().single {it.isFile && it.name.endsWith(".v3.exo")}.writeBytes(byteArrayOf(9,9,9,9))
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertArrayEquals(byteArrayOf(5),coverFile(art,"81").readBytes())
            assertEquals(intent,onWorker {f.source.retirementRecoveryPage().single()}); assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun missingTargetCoverOrChangedOriginalCoverRefusesBeforeAnyAudioRemoval() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val bytes=byteArrayOf(5); coverFile(art,"81").writeBytes(bytes)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            coverFile(art,f.key).delete()
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            coverFile(art,f.key).writeBytes(bytes); coverFile(art,"81").writeBytes(byteArrayOf(7))
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertArrayEquals(byteArrayOf(7),coverFile(art,"81").readBytes()); assertEquals(intent,onWorker {f.source.retirementRecoveryPage().single()})
        } finally {f.close()}
    }
    @Test fun changedDestinationCompletedRowOrMissingConsentRefusesBeforeSourceMutation() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,false,art,work,{true})
            })
            val index=DefaultDownloadIndex(f.f.provider,"publication_target"); val row=index.getDownload(f.key)!!
            index.putDownload(Download(row.request,Download.STATE_COMPLETED,99,100,4,0,0))
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertEquals(intent,onWorker {f.source.retirementRecoveryPage().single()})
        } finally {f.close()}
    }
    @Test fun exactCoveredPendingRecoversMissingOriginalFilesRowAndCoverOnlyAfterRestartAndRenewedConsent() {
        val f=LocalPublicationFixture(); var resumed:OwnedPartitionShelf?=null
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        coverFile(art,"81").writeBytes(byteArrayOf(5))
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            teardown(f.source); f.sourceClosed=true
            // Simulate an interrupted already-confirmed source-disposition stage; target untouched.
            assertTrue(f.f.original.delete()); f.index.removeDownload("81"); assertTrue(coverFile(art,"81").delete())
            val next=f.f.owner(PartitionShelfStart.Resume); resumed=next; onWorker {next.prepare()}
            assertEquals(intent,onWorker {next.retirementRecoveryPage().single()})
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {
                next.completeCoveredOriginalMove(f.target,intent,false,art,work,{true})
            })
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                next.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertEquals(LegacyRetirementPhase.Complete,onWorker {next.retirementRecoveryPage().single().phase})
            assertArrayEquals(byteArrayOf(5),coverFile(art,f.key).readBytes()); assertNotNull(f.target.initializeManager().downloadIndex.getDownload(f.key))
        } finally {resumed?.let(::teardown); f.close()}
    }
    @Test fun activeDestinationReaderBlocksMoveCleanupAndUnboundCoverIntentCannotFinish() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val old=onWorker {f.source.beginCopiedOriginalMove(f.target,copy,completed,true)}
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {
                f.source.completeCoveredOriginalMove(f.target,old,true,art,work,{true})
            })
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work,old)}
            onWorker {
                val reader=f.target.audio.source.createDataSource()
                try {
                    reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(f.key).build())
                    assertEquals(OfflineStore.SavedRemoval.Busy,f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true}))
                    assertTrue(f.f.original.exists()); assertNotNull(f.index.getDownload("81"))
                } finally {reader.close()}
            }
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
        } finally {f.close()}
    }

    @Test fun coveredMoveRetiresReadyDuplicateWithoutRestoringOriginalFallback() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val ready=onWorker {f.source.migrate(CacheMigrationControl(1000,nanoTime={0L}),"81",{Long.MAX_VALUE}) {}}
            assertEquals(MigrationPhase.Ready,ready.phase)
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertFalse(f.f.original.exists()); assertNull(f.index.getDownload("81"))
            assertFalse(onWorker {f.source.audio.contains("81")})
            assertFalse(f.f.root.walkTopDown().any {it.isFile && it.name.endsWith(".v3.exo")})
            assertEquals(LegacyRetirementPhase.Complete,onWorker {f.source.retirementRecoveryPage().single().phase})
            assertTrue(onWorker {f.target.audio.contains(f.key)})
        } finally {f.close()}
    }

    @Test fun composedOriginalMoveCompletesThroughActualCopyPublicationCoverIntentAndSourceCleanup() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val stages=mutableListOf<PartitionOriginalMoveStage>()
        coverFile(art,"81").writeBytes(byteArrayOf(7))
        try {
            val result=onWorker {f.source.moveCompletedOriginalTo(f.target,"81",f.key,true,art,work,{true},boundary={stages+=it})}
            assertEquals(PartitionOriginalMoveResult(PartitionOriginalMoveStage.Complete,OfflineStore.SavedRemoval.Sent,4),result)
            assertEquals(listOf(PartitionOriginalMoveStage.Copied,PartitionOriginalMoveStage.Published,PartitionOriginalMoveStage.Pending),stages)
            assertFalse(f.f.original.exists()); assertNull(f.index.getDownload("81")); assertFalse(coverFile(art,"81").exists())
            assertTrue(onWorker {f.target.audio.contains(f.key)}); assertArrayEquals(byteArrayOf(7),coverFile(art,f.key).readBytes())
            assertEquals(LegacyRetirementPhase.Complete,onWorker {f.source.retirementRecoveryPage().single().phase})
        } finally {f.close()}
    }
    @Test fun composedMoveCancellationAfterCopyLeavesOriginalAndUnpublishedOwnedTarget() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val result=onWorker {f.source.moveCompletedOriginalTo(f.target,"81",f.key,true,art,work,{true},
                boundary={if(it==PartitionOriginalMoveStage.Copied) throw java.io.InterruptedIOException("Cancelled")})}
            assertEquals(PartitionOriginalMoveResult(PartitionOriginalMoveStage.Copied,OfflineStore.SavedRemoval.Failed,4),result)
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertNull(f.target.initializeManager().downloadIndex.getDownload(f.key))
            assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            assertEquals(PartitionSavePhase.Closed,onWorker {f.target.saveRecoveryPage().single().phase})
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun composedMoveCancellationAfterPublicationKeepsBothReadableAndCreatesNoDeletionIntent() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val result=onWorker {f.source.moveCompletedOriginalTo(f.target,"81",f.key,true,art,work,{true},
                boundary={if(it==PartitionOriginalMoveStage.Published) throw java.io.InterruptedIOException("Cancelled")})}
            assertEquals(PartitionOriginalMoveResult(PartitionOriginalMoveStage.Published,OfflineStore.SavedRemoval.Failed,4),result)
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertTrue(onWorker {f.source.audio.contains("81")}); assertTrue(onWorker {f.target.audio.contains(f.key)})
            assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun composedMoveCancellationAfterPendingRequiresSeparateExplicitRecoveryToFinish() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val result=onWorker {f.source.moveCompletedOriginalTo(f.target,"81",f.key,true,art,work,{true},
                boundary={if(it==PartitionOriginalMoveStage.Pending) throw java.io.InterruptedIOException("Cancelled")})}
            assertEquals(PartitionOriginalMoveResult(PartitionOriginalMoveStage.Pending,OfflineStore.SavedRemoval.Failed,4),result)
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            val pending=onWorker {f.source.retirementRecoveryPage().single()}; assertEquals(LegacyRetirementPhase.Pending,pending.phase)
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {
                f.source.completeCoveredOriginalMove(f.target,pending,false,art,work,{true})
            })
            assertTrue(f.f.original.exists())
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                f.source.completeCoveredOriginalMove(f.target,pending,true,art,work,{true})
            })
        } finally {f.close()}
    }

    private fun retirementJournal(owner:OwnedPartitionShelf):LegacyRetirementJournal {
        val startup=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply {isAccessible=true}.get(owner)
        val opened=StorageStartup::class.java.getDeclaredField("opened").apply {isAccessible=true}.get(startup)!!
        return opened.javaClass.getDeclaredField("retirements").apply {isAccessible=true}.get(opened) as LegacyRetirementJournal
    }
    @Test fun cancellationCallbackCannotRelabelDurablyRecordedCoveredIntent() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val journal=retirementJournal(f.source)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work,checkpoint={
                if(journal.page().isNotEmpty()) throw java.io.InterruptedIOException("Late cancellation")
            })}
            assertEquals(intent,onWorker {f.source.retirementRecoveryPage().single()})
            assertTrue(f.f.original.exists()); assertEquals(LegacyRetirementPhase.Pending,intent.phase)
        } finally {f.close()}
    }
    @Test fun cancellationCallbackCannotRelabelDurablyCompletedMoveAsPendingFailure() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val journal=retirementJournal(f.source)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            val result=onWorker {f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true},checkpoint={
                if(journal.page().single().phase==LegacyRetirementPhase.Complete)
                    throw java.io.InterruptedIOException("Late cancellation")
            })}
            assertEquals(OfflineStore.SavedRemoval.Sent,result)
            assertEquals(LegacyRetirementPhase.Complete,onWorker {f.source.retirementRecoveryPage().single().phase})
            assertFalse(f.f.original.exists()); assertNull(f.index.getDownload("81")); assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }

    @Test fun unavailableVolumeOrReplacedDestinationRootRefusesBeforeSourceRemovalAndCanResumeWhenExactRootReturns() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val held=File(f.targetRoot.parentFile,"held-target")
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            f.targetVolume=null
            assertThrows(IOException::class.java) {onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            }}
            f.targetVolume="fixture"
            java.nio.file.Files.move(f.targetRoot.toPath(),held.toPath()); java.nio.file.Files.createDirectory(f.targetRoot.toPath())
            assertThrows(IOException::class.java) {onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            }}
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertEquals(intent,onWorker {f.source.retirementRecoveryPage().single()})
            java.nio.file.Files.delete(f.targetRoot.toPath()); java.nio.file.Files.move(held.toPath(),f.targetRoot.toPath())
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
        } finally {
            f.targetVolume="fixture"
            if(held.exists()) {
                if(f.targetRoot.exists()) java.nio.file.Files.delete(f.targetRoot.toPath())
                java.nio.file.Files.move(held.toPath(),f.targetRoot.toPath())
            }
            f.close()
        }
    }

    @Test fun actualOriginalPublicationRejectsSourceAliasEvenWhenCallerClaimsFreshName() {
        val f=LocalPublicationFixture()
        try {
            val copy=f.prepare()
            val alias=DownloadRequest.Builder("alias",Uri.parse("http://127.0.0.1:7814/alias"))
                .setCustomCacheKey(f.key).build()
            f.index.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertThrows(IOException::class.java) {onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}}
            assertNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun actualMoveFinishRejectsDestinationAliasAddedAfterPendingEvenWhenCallerClaimsSoleOwnership() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val intent=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            val alias=DownloadRequest.Builder("alias",Uri.parse("http://127.0.0.1:7814/alias"))
                .setCustomCacheKey("81").build()
            val targetIndex=DefaultDownloadIndex(f.f.provider,"publication_target")
            targetIndex.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertEquals(intent,onWorker {f.source.retirementRecoveryPage().single()})
            assertEquals(SavedCoverage.Full,onWorker {f.target.audio.inspect(f.key).coverage})
            targetIndex.removeDownload("alias")
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {
                f.source.completeCoveredOriginalMove(f.target,intent,true,art,work,{true})
            })
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun publicStoreMoveUsesActualPartitionControllerAndPublishesScalarStatusOnMain() {
        val f=LocalPublicationFixture(); val app=RuntimeEnvironment.getApplication()
        val field=OfflineStore::class.java.getDeclaredField("store").apply {isAccessible=true}
        val previous=field.get(OfflineStore)
        val mover=OfflineStore::class.java.getDeclaredField("mover").apply {isAccessible=true}
            .get(OfflineStore) as java.util.concurrent.ExecutorService
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        val phone=Shelf.partitioned(f.source,MuonDownloadService::class.java)
        val card=Shelf.partitioned(f.target,MuonCardDownloadService::class.java)
        val recorded=ArrayList<DownloadStatus>(); val removed=ArrayList<String>()
        phone.partitionRemoved={id -> assertSame(Looper.getMainLooper(),Looper.myLooper()); removed+=id}
        val store=OfflineStore.Store(phone,art,PlayedSongEvictor(8192L) {},
            app.getSharedPreferences("partition-public-move-fixture",0),f.f.provider,{status ->
                assertSame(Looper.getMainLooper(),Looper.myLooper()); recorded+=status
            },{},artwork=work,storageBarrier=f.f.barrier)
        store.card=card
        try {
            field.set(OfflineStore,store)
            OfflineStore.move(app,true)
            mover.submit {}.get(10,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("81"),removed); assertEquals(1,recorded.size)
            val status=recorded.single(); assertTrue(status.id.startsWith("saved/"))
            assertEquals(Download.STATE_COMPLETED,status.state); assertEquals(4L,status.bytesDownloaded)
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(status.id))
            assertFalse(f.f.original.exists()); assertNull(f.index.getDownload("81"))
            assertEquals(LegacyRetirementPhase.Complete,onWorker {f.source.retirementRecoveryPage().single().phase})
            assertNull(DownloadMarks.moving); assertTrue(f.f.barrier.quiescent)
            // The partition branch cannot use either shelf's legacy SimpleCache getter.
            assertThrows(IOException::class.java) {phone.cache}
            assertThrows(IOException::class.java) {card.cache}
        } finally {
            mover.submit {}.get(10,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
            field.set(OfflineStore,previous); DownloadMarks.moving=null; f.close()
        }
    }

    @Test fun cancellationAfterActualDestinationPublicationReturnsCommittedRecordBeforeNextCheckpoint() {
        val f=LocalPublicationFixture()
        val targetIndex=PartitionCompletionIndex(f.f.provider,"publication_target")
        try {
            val copy=f.prepare()
            val completion=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10,checkpoint={
                if(targetIndex.find(f.key)!=null) throw java.io.InterruptedIOException("Late publication cancellation")
            })}
            assertEquals(completion,targetIndex.find(f.key))
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun composedCancellationTriggeredByActualPublishedRowReportsPublishedAndPreservesOriginal() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val targetIndex=PartitionCompletionIndex(f.f.provider,"publication_target")
        try {
            val result=onWorker {f.source.moveCompletedOriginalTo(f.target,"81",f.key,true,art,work,{true},checkpoint={
                if(targetIndex.find(f.key)!=null) throw java.io.InterruptedIOException("Cancel when row becomes visible")
            })}
            assertEquals(PartitionOriginalMoveResult(PartitionOriginalMoveStage.Published,OfflineStore.SavedRemoval.Failed,4),result)
            assertNotNull(targetIndex.find(f.key)); assertArrayEquals(payload,f.f.original.readBytes())
            assertNotNull(f.index.getDownload("81")); assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }

    @Test fun actualNewPartitionSaveCanCopyBackLocallyWithFullMetadataAndNoSourceRetirement() {
        val f=LocalPublicationFixture(); val back="saved/local-copy-back"
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            assertEquals(f.key,copy.completion.key); assertEquals(back,copy.destination.allocation.key)
            assertEquals(PartitionSavePhase.Closed,copy.source.phase)
            assertEquals(0,f.f.budget.resident)
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertNull(f.index.getDownload(back))
            assertFalse(onWorker {f.source.audio.contains(back)}) // Closed alone is unpublished.
            assertThrows(IOException::class.java) {onWorker {f.source.audio.inspect(back)}}
            val closed=onWorker {f.source.saveRecoveryPage().single {it.ticket==copy.destination}}
            assertEquals(PartitionSavePhase.Closed,closed.phase)
            assertTrue(onWorker {f.source.retirementRecoveryPage().isEmpty()})
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(f.index.getDownload("81"))
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun savedLocalCopyRefusesNoConsentExistingDestinationAliasAndActiveSourceReader() {
        val f=LocalPublicationFixture(); val back="saved/local-copy-refusal"
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            assertThrows(IOException::class.java) {onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,false)}}
            val alias=DownloadRequest.Builder("alias",Uri.parse("http://127.0.0.1:7814/alias")).setCustomCacheKey(back).build()
            f.index.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertThrows(IOException::class.java) {onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}}
            assertTrue(onWorker {f.source.saveRecoveryPage().isEmpty()})
            f.index.removeDownload("alias")
            val reader=f.target.audio.source.createDataSource()
            try {
                onWorker {
                    reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(f.key).build())
                    assertEquals(4,reader.read(ByteArray(4),0,4))
                    assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT,reader.read(ByteArray(4),0,4))
                }
                assertThrows(IOException::class.java) {onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}}
                assertTrue(onWorker {f.source.saveRecoveryPage().isEmpty()})
            } finally {onWorker {reader.close()}}
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            assertEquals(back,copy.destination.allocation.key); assertEquals(0,f.f.budget.resident)
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key)); assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun savedLocalCopyCancellationPreservesPublishedSourceAndReleasesKnownNativeOwners() {
        val f=LocalPublicationFixture(); val back="saved/local-copy-cancel"; var sawOpen=false
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            assertThrows(java.io.InterruptedIOException::class.java) {onWorker {
                f.target.copyCompletedSavedTo(f.source,f.key,back,true) {
                    if(f.source.saveRecoveryPage().any {it.ticket.allocation.key==back && it.phase==PartitionSavePhase.Open}) {
                        sawOpen=true; throw java.io.InterruptedIOException("Cancel actual saved-copy read")
                    }
                }
            }}
            assertTrue(sawOpen); assertEquals(0,f.f.budget.resident); assertTrue(f.f.barrier.quiescent)
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertTrue(onWorker {f.target.audio.contains(f.key)})
            assertNull(f.index.getDownload(back)); assertTrue(onWorker {f.target.retirementRecoveryPage().isEmpty()})
            assertArrayEquals(payload,f.f.original.readBytes())
        } finally {f.close()}
    }

    @Test fun actualSavedCopyPublicationPreservesRequestMetadataAndSourceAcrossBothRestarts() {
        val f=LocalPublicationFixture(); val back="saved/published-copy-back"
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            val completion=onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20)}
            assertEquals(back,completion.key); assertEquals(4L,completion.length); assertEquals(0,f.f.budget.resident)
            val record=f.index.getDownload(back)!!
            assertEquals(f.request.uri,record.request.uri); assertArrayEquals(f.request.data,record.request.data)
            assertEquals(back,record.request.customCacheKey)
            assertEquals(SavedCoverage.Full,onWorker {f.source.audio.inspect(back).coverage})
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            teardown(f.source); f.sourceClosed=true; teardown(f.target); f.targetClosed=true
            val resumedSource=f.f.owner(PartitionShelfStart.Resume); val resumedTarget=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {resumedSource.prepare(); resumedTarget.prepare()}
                await(resumedSource.initializeManager()) {true}; await(resumedTarget.initializeManager()) {true}
                assertEquals(SavedCoverage.Full,onWorker {resumedSource.audio.inspect(back).coverage})
                assertEquals(SavedCoverage.Full,onWorker {resumedTarget.audio.inspect(f.key).coverage})
                val reader=resumedSource.audio.source.createDataSource()
                try {
                    onWorker {
                        reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(back).build())
                        val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                    }
                } finally {onWorker {reader.close()}}
                assertTrue(onWorker {resumedSource.retirementRecoveryPage().isEmpty()})
                assertTrue(onWorker {resumedTarget.retirementRecoveryPage().isEmpty()})
            } finally {teardown(resumedTarget); teardown(resumedSource)}
            assertArrayEquals(payload,f.f.original.readBytes())
        } finally {f.close()}
    }
    @Test fun savedCopyPublicationRejectsActualSourceAliasAndChangedCompletionWithoutDeletingEitherCopy() {
        val f=LocalPublicationFixture(); val back="saved/publication-alias"
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            val targetIndex=DefaultDownloadIndex(f.f.provider,"publication_target")
            val alias=DownloadRequest.Builder("alias",Uri.parse("http://127.0.0.1:7814/alias")).setCustomCacheKey(back).build()
            targetIndex.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertThrows(IOException::class.java) {onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20)}}
            assertNull(f.index.getDownload(back)); targetIndex.removeDownload("alias")
            val current=targetIndex.getDownload(f.key)!!
            targetIndex.putDownload(Download(current.request,Download.STATE_COMPLETED,10,11,4,Download.STOP_REASON_NONE,Download.FAILURE_REASON_NONE))
            assertThrows(IOException::class.java) {onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20)}}
            assertNull(f.index.getDownload(back)); assertTrue(f.f.barrier.quiescent)
            assertArrayEquals(payload,f.f.original.readBytes()); assertNotNull(targetIndex.getDownload(f.key))
        } finally {f.close()}
    }
    @Test fun savedCopyPublicationDefersLateCancellationUntilConfirmedReceiptAndSourceClosure() {
        val f=LocalPublicationFixture(); val back="saved/publication-late-cancel"
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            val index=PartitionCompletionIndex(f.f.provider,"owned_partition")
            val completion=onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20) {
                if(index.find(back)!=null) throw java.io.InterruptedIOException("Cancel after actual publication")
            }}
            assertEquals(completion,index.find(back)); assertEquals(0,f.f.budget.resident)
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertTrue(f.f.barrier.quiescent); assertTrue(onWorker {f.target.retirementRecoveryPage().isEmpty()})
        } finally {f.close()}
    }

    @Test fun actualNewSourceMoveIntentBindsBothCopiesAndCoverAndSurvivesBothShelvesRestart() {
        val f=LocalPublicationFixture(); val back="saved/move-intent-back"
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            coverFile(art,f.key).writeBytes(byteArrayOf(1,2,3))
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            val completed=onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20)}
            val pending=onWorker {f.target.beginCoveredSavedMove(f.source,copy,completed,true,art,work)}
            assertEquals(PartitionSavedMovePhase.Pending,pending.phase)
            assertEquals(f.key,pending.binding.key); assertEquals(back,pending.binding.destination.ticket.allocation.key)
            assertEquals(3L,pending.binding.cover.length)
            assertNotNull(pending.binding.sourceTree)
            assertTrue(requireNotNull(pending.binding.sourceTree).files.any {it.path=="index/native-v1.db"})
            assertEquals(0,f.f.budget.resident) // Actual source native owner closed before exact snapshot.
            assertEquals(pending.binding,PartitionSavedMoveBinding.decode(pending.binding.encode()))
            assertFalse(onWorker {f.target.audio.contains(f.key)}) // Durable intent blocks source routing.
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {f.target.removeCompleted(f.key) {true}})
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertArrayEquals(byteArrayOf(1,2,3),coverFile(art,f.key).readBytes())
            assertArrayEquals(byteArrayOf(1,2,3),coverFile(art,back).readBytes())
            assertEquals(SavedCoverage.Full,onWorker {f.source.audio.inspect(back).coverage})
            teardown(f.source); f.sourceClosed=true; teardown(f.target); f.targetClosed=true
            val resumedSource=f.f.owner(PartitionShelfStart.Resume); val resumedTarget=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {resumedSource.prepare(); resumedTarget.prepare()}
                await(resumedSource.initializeManager()) {true}; await(resumedTarget.initializeManager()) {true}
                assertEquals(listOf(pending),onWorker {resumedTarget.moveRecoveryPage()})
                assertFalse(onWorker {resumedTarget.audio.contains(f.key)})
                assertEquals(SavedCoverage.Full,onWorker {resumedSource.audio.inspect(back).coverage})
                assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            } finally {teardown(resumedTarget); teardown(resumedSource)}
            assertArrayEquals(payload,f.f.original.readBytes()); assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun savedMoveIntentRefusesNoConsentOrActualReaderBeforePersistingAnyIntent() {
        val f=LocalPublicationFixture(); val back="saved/move-intent-refusal"
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            val completed=onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20)}
            assertThrows(IOException::class.java) {onWorker {f.target.beginCoveredSavedMove(f.source,copy,completed,false,art,work)}}
            val reader=f.target.audio.source.createDataSource()
            try {
                onWorker {
                    reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(f.key).build())
                    assertEquals(4,reader.read(ByteArray(4),0,4))
                    assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT,reader.read(ByteArray(4),0,4))
                }
                assertThrows(IOException::class.java) {onWorker {f.target.beginCoveredSavedMove(f.source,copy,completed,true,art,work)}}
                assertTrue(onWorker {f.target.moveRecoveryPage().isEmpty()})
            } finally {onWorker {reader.close()}}
            assertEquals(PartitionSavedMovePhase.Pending,onWorker {f.target.beginCoveredSavedMove(f.source,copy,completed,true,art,work)}.phase)
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }

    private inner class NewSourceMoveFixture {
        val f=LocalPublicationFixture(); val back="saved/finished-new-source"
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        val pending:PartitionSavedMoveRecord
        val originalAllocation:File
        init {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            coverFile(art,f.key).writeBytes(byteArrayOf(2,4,6))
            val copy=onWorker {f.target.copyCompletedSavedTo(f.source,f.key,back,true)}
            val completed=onWorker {f.target.publishCopiedSavedTo(f.source,copy,true,{true},20)}
            pending=onWorker {f.target.beginCoveredSavedMove(f.source,copy,completed,true,art,work)}
            originalAllocation=File(f.targetRoot,"resources/"+pending.binding.source.ticket.allocation.directory)
        }
        fun finish(consent:Boolean=true,record:PartitionSavedMoveRecord=pending,checkpoint:()->Unit={})=onWorker {
            f.target.completeCoveredSavedMove(f.source,record,consent,art,work,{true},checkpoint)
        }
        fun sourceRecord()=PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key)
        fun close()=f.close()
    }
    @Test fun actualNewSourceMoveFinishesTreeRowAndOwnCoverAndSurvivesRestart() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
            assertFalse(n.originalAllocation.exists()); assertNull(n.sourceRecord()); assertFalse(coverFile(n.art,f.key).exists())
            assertArrayEquals(byteArrayOf(2,4,6),coverFile(n.art,n.back).readBytes())
            assertEquals(PartitionSavedMovePhase.Complete,onWorker {f.target.moveRecoveryPage().single().phase})
            assertEquals(OfflineStore.SavedRemoval.Waiting,n.finish())
            teardown(f.source); f.sourceClosed=true; teardown(f.target); f.targetClosed=true
            val target=f.f.owner(PartitionShelfStart.Resume); val source=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {target.prepare(); source.prepare()}
                assertEquals(PartitionSavedMovePhase.Complete,onWorker {source.moveRecoveryPage().single().phase})
                assertFalse(onWorker {source.audio.contains(f.key)})
                onWorker {
                    val reader=target.audio.source.createDataSource()
                    try {
                        assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(n.back).build()))
                        val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                    } finally {reader.close()}
                }
            } finally {teardown(source);teardown(target)}
            assertTrue(f.f.barrier.quiescent)
        } finally {n.close(); assertEquals(0,f.f.budget.resident)}
    }
    @Test fun actualNewSourcePartialAudioUnlinkCanResumeFromExactPendingAfterBothShelvesRestart() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            val audio=n.originalAllocation.walkTopDown().single {it.name.endsWith(".v3.exo")}
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish {if(!audio.exists()) throw java.io.InterruptedIOException("Stop after actual unlink")})
            assertFalse(audio.exists()); assertNotNull(n.sourceRecord()); assertTrue(n.originalAllocation.exists())
            assertEquals(n.pending,onWorker {f.target.moveRecoveryPage().single()}); assertTrue(f.f.barrier.quiescent)
            teardown(f.source); f.sourceClosed=true; teardown(f.target); f.targetClosed=true
            val target=f.f.owner(PartitionShelfStart.Resume); val source=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {target.prepare();source.prepare()}
                assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {source.completeCoveredSavedMove(target,n.pending,false,n.art,n.work,{true})})
                assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {source.completeCoveredSavedMove(target,n.pending,true,n.art,n.work,{true})})
                assertFalse(n.originalAllocation.exists()); assertNull(n.sourceRecord())
                assertEquals(SavedCoverage.Full,onWorker {target.audio.inspect(n.back).coverage})
            } finally {teardown(source);teardown(target)}
        } finally {n.close(); assertEquals(0,f.f.budget.resident)}
    }
    @Test fun newSourceMoveRejectsMissingConsentStaleIntentAndChangedTargetBeforeAnySourceUnlink() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.NotOwned,n.finish(false))
            assertEquals(OfflineStore.SavedRemoval.Waiting,n.finish(record=n.pending.copy(token="00000000-0000-0000-0000-000000000001")))
            val target=File(f.f.root,"resources/"+n.pending.binding.destination.ticket.allocation.directory)
                .walkTopDown().single {it.name.endsWith(".v3.exo")}
            target.writeBytes(byteArrayOf(9,9,9,9))
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish())
            assertTrue(n.originalAllocation.walkTopDown().any {it.name.endsWith(".v3.exo")})
            assertNotNull(n.sourceRecord()); assertTrue(coverFile(n.art,f.key).exists())
            assertEquals(n.pending,onWorker {f.target.moveRecoveryPage().single()}); assertTrue(f.f.barrier.quiescent)
        } finally {n.close()}
    }
    @Test fun newSourceMoveRejectsChangedSourceCompletionAndForeignTreeWithoutRemovingBytes() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            val index=DefaultDownloadIndex(f.f.provider,"publication_target")
            val before=index.getDownload(f.key)!!
            index.putDownload(Download(before.request,Download.STATE_COMPLETED,1,99,4,0,0))
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish())
            index.putDownload(before)
            File(n.originalAllocation,"metadata/foreign").writeText("Keep")
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish())
            assertNotNull(n.sourceRecord()); assertTrue(n.originalAllocation.walkTopDown().any {it.name.endsWith(".v3.exo")})
            assertTrue(f.f.barrier.quiescent)
        } finally {n.close()}
    }
    @Test fun actualNewSourceMoveResumesAfterKnownTerminalRowRemovalAndDoesNotRelabelDurableComplete() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish {if(n.sourceRecord()==null) throw java.io.InterruptedIOException("Stop after row")})
            assertFalse(n.originalAllocation.exists()); assertNull(n.sourceRecord()); assertTrue(coverFile(n.art,f.key).exists())
            assertTrue(f.f.barrier.quiescent); assertEquals(n.pending,onWorker {f.target.moveRecoveryPage().single()})
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
            assertFalse(coverFile(n.art,f.key).exists())
        } finally {n.close()}
    }

    @Test fun composedNewSourceMoveUsesActualOwnershipAndCompletesWithoutUpstream() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
        val work=coverWork(art,f.f.barrier); val back="saved/composed-new-back"
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            coverFile(art,f.key).writeBytes(byteArrayOf(4,5,6))
            val boundaries=ArrayList<PartitionOriginalMoveStage>()
            val result=onWorker {f.target.moveCompletedTo(f.source,f.key,back,true,art,work,{true},boundary={boundaries+=it})}
            assertEquals(PartitionOriginalMoveStage.Complete,result.stage); assertEquals(OfflineStore.SavedRemoval.Sent,result.outcome)
            assertEquals(4L,result.bytes)
            assertEquals(listOf(PartitionOriginalMoveStage.Copied,PartitionOriginalMoveStage.Published,PartitionOriginalMoveStage.Pending),boundaries)
            assertNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertEquals(SavedCoverage.Full,onWorker {f.source.audio.inspect(back).coverage})
            assertArrayEquals(byteArrayOf(4,5,6),coverFile(art,back).readBytes()); assertFalse(coverFile(art,f.key).exists())
            assertTrue(f.f.barrier.quiescent)
        } finally {f.close()}
    }
    @Test fun composedNewMoveCancellationPreservesSourceOrExactPendingAtEachCommittedBoundary() {
        for(stop in listOf(PartitionOriginalMoveStage.Copied,PartitionOriginalMoveStage.Published,PartitionOriginalMoveStage.Pending)) {
            val f=LocalPublicationFixture("saved/cancel-source-"+stop.name); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier)
            val work=coverWork(art,f.f.barrier); val back="saved/cancel-new-"+stop.name
            try {
                val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
                var cancelled=false
                val result=onWorker {f.target.moveCompletedTo(f.source,f.key,back,true,art,work,{true},
                    checkpoint={if(cancelled) throw java.io.InterruptedIOException("Cancel at boundary")},
                    boundary={if(it==stop) cancelled=true})}
                assertEquals(stop,result.stage); assertEquals(OfflineStore.SavedRemoval.Failed,result.outcome)
                assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
                val completed=PartitionCompletionIndex(f.f.provider,"owned_partition").find(back)
                if(stop==PartitionOriginalMoveStage.Copied) assertNull(completed) else assertNotNull(completed)
                if(stop==PartitionOriginalMoveStage.Pending) {
                    val pending=onWorker {f.target.moveRecoveryPage().single()}
                    assertFalse(onWorker {f.target.audio.contains(f.key)})
                    val retry=onWorker {f.target.moveCompletedTo(f.source,f.key,"saved/do-not-recopy",true,art,work,{true})}
                    assertEquals(OfflineStore.SavedRemoval.Unavailable,retry.outcome)
                    assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {f.target.completeCoveredSavedMove(f.source,pending,true,art,work,{true})})
                } else assertEquals(SavedCoverage.Full,onWorker {f.target.audio.inspect(f.key).coverage})
                assertTrue(f.f.barrier.quiescent)
            } finally {f.close()}
        }
    }
    @Test fun publicStoreMoveDispatchesNewPartitionSourceAndKeepsCallbacksOnMain() {
        val f=LocalPublicationFixture(); val app=RuntimeEnvironment.getApplication()
        val field=OfflineStore::class.java.getDeclaredField("store").apply {isAccessible=true}; val previous=field.get(OfflineStore)
        val mover=OfflineStore::class.java.getDeclaredField("mover").apply {isAccessible=true}.get(OfflineStore) as java.util.concurrent.ExecutorService
        val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        val phone=Shelf.partitioned(f.source,MuonDownloadService::class.java); val card=Shelf.partitioned(f.target,MuonCardDownloadService::class.java)
        val recorded=ArrayList<DownloadStatus>(); val removed=ArrayList<String>()
        card.partitionRemoved={id ->assertSame(Looper.getMainLooper(),Looper.myLooper());removed+=id}
        val store=OfflineStore.Store(phone,art,PlayedSongEvictor(8192L) {},app.getSharedPreferences("partition-new-public-move",0),
            f.f.provider,{status ->assertSame(Looper.getMainLooper(),Looper.myLooper());recorded+=status},{},artwork=work,storageBarrier=f.f.barrier)
        store.card=card
        try {
            val original=f.prepare(); onWorker {f.source.publishCopiedOriginalTo(f.target,original,true,{true},10)}
            field.set(OfflineStore,store); OfflineStore.move(app,false)
            mover.submit {}.get(10,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(f.key),removed); assertEquals(1,recorded.size)
            assertNotEquals(f.key,recorded.single().id); assertEquals(4L,recorded.single().bytesDownloaded)
            assertNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertNotNull(PartitionCompletionIndex(f.f.provider,"owned_partition").find(recorded.single().id))
            assertEquals(PartitionSavedMovePhase.Complete,onWorker {f.target.moveRecoveryPage().single().phase})
            assertThrows(IOException::class.java) {phone.cache};assertThrows(IOException::class.java) {card.cache}
            assertNull(DownloadMarks.moving); assertTrue(f.f.barrier.quiescent)
        } finally {
            mover.submit {}.get(10,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            field.set(OfflineStore,previous);DownloadMarks.moving=null;f.close()
        }
    }
    @Test fun newSourceMoveRecoveryRejectsActualCrossShelfAliasUntilExplicitlyResolved() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            val index=DefaultDownloadIndex(f.f.provider,"owned_partition")
            val alias=DownloadRequest.Builder("alias-new-source",Uri.parse("http://127.0.0.1:7814/alias"))
                .setCustomCacheKey(f.key).build()
            index.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish()); assertNotNull(n.sourceRecord())
            assertTrue(n.originalAllocation.walkTopDown().any {it.name.endsWith(".v3.exo")})
            index.removeDownload("alias-new-source")
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish()); assertTrue(f.f.barrier.quiescent)
        } finally {n.close()}
    }

    @Test fun explicitNewSourceRecoveryRequiresExactTokenAndRenewedConsentAndReturnsOnlyScalarResult() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {f.target.recoverCompletedMoveTo(f.source,f.key,n.pending.token,false,n.art,n.work,{true})}.outcome)
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {f.target.recoverCompletedMoveTo(f.source,f.key,"00000000-0000-0000-0000-000000000001",true,n.art,n.work,{true})}.outcome)
            assertNotNull(n.sourceRecord()); assertTrue(n.originalAllocation.exists())
            val result=onWorker {f.target.recoverCompletedMoveTo(f.source,f.key,n.pending.token,true,n.art,n.work,{true})}
            assertEquals(OfflineStore.SavedRemoval.Sent,result.outcome); assertEquals(n.back,result.destination);assertEquals(4L,result.bytes)
            assertNull(n.sourceRecord()); assertFalse(n.originalAllocation.exists())
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {f.target.recoverCompletedMoveTo(f.source,f.key,n.pending.token,true,n.art,n.work,{true})}.outcome)
        } finally {n.close()}
    }
    @Test fun sameRecoveryEntryPointAlsoFinishesAnExactOriginalMoveAndRejectsMissingEntries() {
        val f=LocalPublicationFixture(); val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier); val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare(); val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            val pending=onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {f.source.recoverCompletedMoveTo(f.target,"missing",pending.token,true,art,work,{true})}.outcome)
            val result=onWorker {f.source.recoverCompletedMoveTo(f.target,"81",pending.token,true,art,work,{true})}
            assertEquals(OfflineStore.SavedRemoval.Sent,result.outcome);assertEquals(f.key,result.destination)
            assertFalse(f.f.original.exists());assertNull(f.index.getDownload("81"))
        } finally {f.close()}
    }
    @Test fun publicRecoveryUsesExistingWorkerAndScalarMainCallbacksOnlyAfterConfirmedFinish() {
        val n=NewSourceMoveFixture(); val f=n.f; val app=RuntimeEnvironment.getApplication()
        val field=OfflineStore::class.java.getDeclaredField("store").apply {isAccessible=true};val previous=field.get(OfflineStore)
        val mover=OfflineStore::class.java.getDeclaredField("mover").apply {isAccessible=true}.get(OfflineStore) as java.util.concurrent.ExecutorService
        val phone=Shelf.partitioned(f.source,MuonDownloadService::class.java);val card=Shelf.partitioned(f.target,MuonCardDownloadService::class.java)
        val removed=ArrayList<String>();val recorded=ArrayList<DownloadStatus>()
        card.partitionRemoved={assertSame(Looper.getMainLooper(),Looper.myLooper());removed+=it}
        val store=OfflineStore.Store(phone,n.art,PlayedSongEvictor(8192L) {},app.getSharedPreferences("partition-public-recovery",0),f.f.provider,
            {assertSame(Looper.getMainLooper(),Looper.myLooper());recorded+=it},{},artwork=n.work,storageBarrier=f.f.barrier)
        store.card=card
        try {
            field.set(OfflineStore,store)
            assertFalse(OfflineStore.recoverMove(app,true,f.key,n.pending.token,false));assertNotNull(n.sourceRecord())
            assertTrue(OfflineStore.recoverMove(app,true,f.key,n.pending.token,true))
            assertFalse(OfflineStore.recoverMove(app,true,f.key,n.pending.token,true)) // Same serial exclusion still held.
            mover.submit {}.get(10,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(f.key),removed);assertEquals(listOf(n.back),recorded.map {it.id})
            assertNull(n.sourceRecord());assertTrue(f.f.barrier.quiescent)
        } finally {
            mover.submit {}.get(10,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            field.set(OfflineStore,previous);n.close()
        }
    }
    @Test fun actualRecoveryCursorCloseUncertaintyQuarantinesTheRealShelfGate() {
        val f=Fixture();val owner=f.owner();onWorker {owner.prepare()}
        val startup=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply {isAccessible=true}.get(owner)
        val resources=StorageStartup::class.java.getDeclaredField("opened").apply {isAccessible=true}.get(startup)!!
        val field=resources.javaClass.getDeclaredField("moves").apply {isAccessible=true}
        onWorker {(field.get(resources) as PartitionSavedMoveJournal).close()}
        var fail=false;var actual:android.database.sqlite.SQLiteDatabase?=null;var child:java.io.Closeable?=null;var attempts=0
        val journal=PartitionSavedMoveJournal(f.root,false,openDatabase={file,_ ->
            android.database.sqlite.SQLiteDatabase.openDatabase(file.path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).also {actual=it}
        },closeIo={if(fail) {child=it;attempts++;throw IOException("Unknown real recovery cursor close")} else it.close()})
        onWorker {journal.open()};field.set(resources,journal);fail=true
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) {onWorker {owner.moveRecoveryPage()}}
            assertFalse(f.barrier.allowsShared);assertTrue(actual!!.isOpen);assertEquals(1,attempts)
            assertThrows(IOException::class.java) {onWorker {owner.moveRecoveryPage()}}
            assertThrows(IOException::class.java) {onWorker {journal.close()}};assertEquals(1,attempts);assertEquals(0,f.budget.resident)
        } finally {
            // Fixture knows the injected callback never reached actual close. Do not clear/reset the
            // production barrier or retry the uncertain wrapper. No native/cache owner was opened.
            child?.close();actual?.close()
            (resources.javaClass.getDeclaredField("workers").apply {isAccessible=true}.get(resources) as java.util.concurrent.ExecutorService).shutdown()
            // Manager was never initialized and native pools never opened. Close the test's known
            // idle actual read-only connection directly; gated wrappers must stay quarantined.
            val legacy=resources.javaClass.getDeclaredField("legacy").apply {isAccessible=true}.get(resources)
            (LegacyTransitionOwner::class.java.getDeclaredField("index").apply {isAccessible=true}.get(legacy) as android.database.sqlite.SQLiteDatabase?)?.close()
            for(name in listOf("saves","migrations","catalog"))
                (resources.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(resources) as java.io.Closeable).close()
            onWorker {(resources.javaClass.getDeclaredField("retirements").apply {isAccessible=true}.get(resources) as LegacyRetirementJournal).close()}
            onWorker {(resources.javaClass.getDeclaredField("deletes").apply {isAccessible=true}.get(resources) as PartitionSavedDeleteJournal).close()}
            f.provider.close()
        }
    }

    @Test fun boundedMoveSummariesExposeOnlyScalarsAndDropFinishedEntries() {
        val n=NewSourceMoveFixture(); val f=n.f
        try {
            val page=onWorker {f.target.pendingMoveSummaries()}
            assertEquals(listOf(PartitionStorageRecoverySummary(f.key,n.pending.token,4L,PartitionRecoveryAction.Move)),page.entries)
            assertEquals(f.key,page.after); assertFalse(page.hasMore)
            assertTrue(onWorker {f.target.pendingMoveSummaries(f.key)}.entries.isEmpty())
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
            assertTrue(onWorker {f.target.pendingMoveSummaries()}.entries.isEmpty())
        } finally {n.close()}
    }
    @Test fun originalDeleteIntentAdvancesMoveSummaryCursorWithoutOfferingDeleteAsMove() {
        val f=LocalPublicationFixture()
        try {
            onWorker {f.source.beginOriginalRetirement("81",true)}
            val page=onWorker {f.source.pendingMoveSummaries()}
            assertTrue(page.entries.isEmpty()); assertEquals("81",page.after); assertFalse(page.hasMore)
            assertNotNull(f.index.getDownload("81"))
        } finally {f.close()}
    }

    @Test fun actualSavedDeleteIntentPreservesFilesAndRowsSuppressesReadsAndSurvivesRestart() {
        val f=LocalPublicationFixture();val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier);val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare();onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            coverFile(art,f.key).writeBytes(byteArrayOf(9,8,7))
            val pending=onWorker {f.target.beginSavedDelete(f.key,true,art,work,{true})}
            assertEquals(PartitionSavedDeletePhase.Pending,pending.phase);assertEquals(f.key,pending.binding.key)
            assertEquals(4L,pending.binding.length);assertEquals(3L,pending.binding.cover.length)
            val allocation=File(f.targetRoot,"resources/"+pending.binding.ticket.allocation.directory)
            assertTrue(allocation.walkTopDown().any {it.name.endsWith(".v3.exo")})
            assertNotNull(PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key))
            assertArrayEquals(byteArrayOf(9,8,7),coverFile(art,f.key).readBytes())
            assertFalse(f.target.audio.contains(f.key));assertTrue(f.target.claimsName(f.key))
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {f.target.removeCompleted(f.key) {true}})
            assertThrows(IOException::class.java) {onWorker {f.target.copyCompletedSavedTo(f.source,f.key,"saved/conflict",true)}}
            assertEquals(listOf(pending),onWorker {f.target.deleteRecoveryPage()})
            teardown(f.target);f.targetClosed=true
            val resumed=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {resumed.prepare()};assertFalse(resumed.audio.contains(f.key))
                assertEquals(listOf(pending),onWorker {resumed.deleteRecoveryPage()})
                assertTrue(allocation.exists());assertTrue(resumed.claimsName(f.key))
            } finally {teardown(resumed)}
        } finally {f.close()}
    }
    @Test fun savedDeleteIntentNeedsConsentAndActualReaderReleaseEvenAfterEof() {
        val f=LocalPublicationFixture();val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier);val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare();onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            assertThrows(IOException::class.java) {onWorker {f.target.beginSavedDelete(f.key,false,art,work,{true})}}
            val reader=f.target.audio.source.createDataSource()
            try {
                reader.open(DataSpec.Builder().setUri("muon-saved:delete").setKey(f.key).build())
                assertEquals(4,reader.read(ByteArray(4),0,4));assertEquals(-1,reader.read(ByteArray(1),0,1))
                assertThrows(IOException::class.java) {onWorker {f.target.beginSavedDelete(f.key,true,art,work,{true})}}
                assertTrue(onWorker {f.target.deleteRecoveryPage()}.isEmpty())
            } finally {reader.close()}
            assertEquals(PartitionSavedDeletePhase.Pending,onWorker {f.target.beginSavedDelete(f.key,true,art,work,{true})}.phase)
            assertEquals(0,f.f.budget.resident)
        } finally {f.close()}
    }
    @Test fun savedDeleteIntentRefusesActualIndexAliasAndOtherShelfClaimWithoutRecordingAuthority() {
        val f=LocalPublicationFixture();val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier);val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare();onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            assertThrows(IOException::class.java) {onWorker {f.target.beginSavedDelete(f.key,true,art,work,{false})}}
            val index=DefaultDownloadIndex(f.f.provider,"publication_target")
            val alias=DownloadRequest.Builder("foreign-alias",Uri.parse("http://127.0.0.1:7814/alias")).setCustomCacheKey(f.key).build()
            index.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertThrows(IOException::class.java) {onWorker {f.target.beginSavedDelete(f.key,true,art,work,{true})}}
            assertTrue(onWorker {f.target.deleteRecoveryPage()}.isEmpty());assertNotNull(index.getDownload(f.key))
            index.removeDownload("foreign-alias")
            assertEquals(PartitionSavedDeletePhase.Pending,onWorker {f.target.beginSavedDelete(f.key,true,art,work,{true})}.phase)
        } finally {f.close()}
    }
    @Test fun replacingDeleteJournalMakesActualShelfUnavailableWithoutOpeningAReplacementOwner() {
        val f=Fixture();val owner=f.owner()
        try {
            onWorker {owner.prepare()}
            val file=File(f.root,"partition-deletes-v1.db");val kept=File(f.root,"retained-deletes.db")
            assertTrue(file.renameTo(kept));file.writeText("foreign")
            assertFalse(owner.isAvailable)
            assertThrows(IOException::class.java) {onWorker {owner.deleteRecoveryPage()}}
            assertTrue(kept.isFile)
            assertTrue(file.delete());assertTrue(kept.renameTo(file))
        } finally {teardown(owner);f.provider.close()}
    }
    private inner class SavedDeleteFixture {
        val f=LocalPublicationFixture();val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier);val work=coverWork(art,f.f.barrier)
        val pending:PartitionSavedDeleteRecord;val allocation:File
        init {
            val copy=f.prepare();onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            coverFile(art,f.key).writeBytes(byteArrayOf(1,3,5))
            pending=onWorker {f.target.beginSavedDelete(f.key,true,art,work,{true})}
            allocation=File(f.targetRoot,"resources/"+pending.binding.ticket.allocation.directory)
        }
        fun row()=PartitionCompletionIndex(f.f.provider,"publication_target").find(f.key)
        fun finish(consent:Boolean=true,record:PartitionSavedDeleteRecord=pending,checkpoint:()->Unit={})=onWorker {
            f.target.completeSavedDelete(record,consent,art,work,{true},checkpoint)
        }
        fun close()=f.close()
    }
    @Test fun actualSavedDeleteFinishesTreeTerminalRowOwnCoverAndKeepsRestartTombstone() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
            assertFalse(n.allocation.exists());assertNull(n.row());assertFalse(coverFile(n.art,f.key).exists())
            assertEquals(PartitionSavedDeletePhase.Complete,onWorker {f.target.deleteRecoveryPage().single().phase})
            assertFalse(f.target.audio.contains(f.key));assertTrue(f.target.claimsName(f.key));assertTrue(f.f.original.exists())
            assertEquals(OfflineStore.SavedRemoval.Waiting,n.finish())
            teardown(f.target);f.targetClosed=true;val resumed=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {resumed.prepare()};assertFalse(resumed.audio.contains(f.key));assertTrue(resumed.claimsName(f.key))
                assertEquals(PartitionSavedDeletePhase.Complete,onWorker {resumed.deleteRecoveryPage().single().phase})
            } finally {teardown(resumed)}
        } finally {n.close();assertEquals(0,f.f.budget.resident)}
    }
    @Test fun actualSavedDeleteResumesPartialAudioRemovalAfterRestartOnlyWithRenewedConsent() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            val audio=n.allocation.walkTopDown().single {it.name.endsWith(".v3.exo")}
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish {if(!audio.exists()) throw java.io.InterruptedIOException("Stop after unlink")})
            assertFalse(audio.exists());assertNotNull(n.row());assertTrue(n.allocation.exists());assertTrue(f.f.barrier.quiescent)
            teardown(f.target);f.targetClosed=true;val resumed=f.target(PartitionShelfStart.Resume)
            try {
                onWorker {resumed.prepare()}
                assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {resumed.completeSavedDelete(n.pending,false,n.art,n.work,{true})})
                assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {resumed.completeSavedDelete(n.pending,true,n.art,n.work,{true})})
                assertFalse(n.allocation.exists());assertNull(n.row());assertFalse(coverFile(n.art,f.key).exists())
            } finally {teardown(resumed)}
        } finally {n.close()}
    }
    @Test fun actualSavedDeleteRecoversKnownRemovedTerminalRecordWithoutRelabellingLateComplete() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish {if(n.row()==null) throw java.io.InterruptedIOException("Stop after record")})
            assertFalse(n.allocation.exists());assertNull(n.row());assertTrue(coverFile(n.art,f.key).exists())
            assertEquals(n.pending,onWorker {f.target.deleteRecoveryPage().single()});assertTrue(f.f.barrier.quiescent)
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
            assertEquals(PartitionSavedDeletePhase.Complete,onWorker {f.target.deleteRecoveryPage().single().phase})
            assertFalse(coverFile(n.art,f.key).exists())
        } finally {n.close()}
    }
    @Test fun savedDeleteRefusesMissingConsentStaleTokenChangedRecordAndForeignTreeBeforeAnyRemoval() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.NotOwned,n.finish(false))
            assertEquals(OfflineStore.SavedRemoval.Waiting,n.finish(record=n.pending.copy(token="00000000-0000-0000-0000-000000000001")))
            val index=DefaultDownloadIndex(f.f.provider,"publication_target");val before=index.getDownload(f.key)!!
            index.putDownload(Download(before.request,Download.STATE_COMPLETED,1,99,4,0,0))
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish());index.putDownload(before)
            val foreign=File(n.allocation,"metadata/foreign");foreign.writeText("Preserve")
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish())
            assertTrue(n.allocation.walkTopDown().any {it.name.endsWith(".v3.exo")});assertNotNull(n.row())
            assertTrue(foreign.isFile);assertTrue(coverFile(n.art,f.key).exists());assertTrue(f.f.barrier.quiescent)
        } finally {n.close()}
    }
    @Test fun savedDeleteRefusesChangedOwnCoverAndActualIndexAliasWithoutMutation() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            coverFile(n.art,f.key).writeBytes(byteArrayOf(9,9,9))
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish())
            coverFile(n.art,f.key).writeBytes(byteArrayOf(1,3,5))
            val index=DefaultDownloadIndex(f.f.provider,"publication_target")
            val alias=DownloadRequest.Builder("delete-alias",Uri.parse("http://127.0.0.1:7814/alias")).setCustomCacheKey(f.key).build()
            index.putDownload(Download(alias,Download.STATE_FAILED,1,2,0,Download.STOP_REASON_NONE,Download.FAILURE_REASON_UNKNOWN))
            assertEquals(OfflineStore.SavedRemoval.Failed,n.finish());assertTrue(n.allocation.exists());assertNotNull(n.row())
            index.removeDownload("delete-alias");assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
        } finally {n.close()}
    }
    @Test fun freshDeleteDispatchNeverAdoptsExistingPendingAndRecoveryRequiresExactToken() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {f.target.removeSavedCompleted(f.key,true,n.art,n.work,{true})})
            assertEquals(OfflineStore.SavedRemoval.NotOwned,onWorker {f.target.recoverCompletedDelete(f.key,n.pending.token,false,n.art,n.work,{true})})
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {f.target.recoverCompletedDelete(f.key,"00000000-0000-0000-0000-000000000001",true,n.art,n.work,{true})})
            assertTrue(n.allocation.exists());assertNotNull(n.row())
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {f.target.recoverCompletedDelete(f.key,n.pending.token,true,n.art,n.work,{true})})
            assertFalse(n.allocation.exists());assertNull(n.row())
        } finally {n.close()}
    }
    @Test fun originalDeleteRecoveryUsesBoundPendingAndNeverTreatsMoveOrFilesOnlyIntentAsDelete() {
        val f=Fixture("42");val index=seedOriginal(f);val owner=f.owner();val art=DownloadArt(folders.newFolder(),barrier=f.barrier);val work=coverWork(art,f.barrier)
        try {
            onWorker {owner.prepare()};val pending=boundOriginalIntent(f,owner)
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {owner.recoverCompletedDelete("42","00000000-0000-0000-0000-000000000001",true,art,work,{true})})
            assertEquals(OfflineStore.SavedRemoval.Sent,onWorker {owner.recoverCompletedDelete("42",pending.token,true,art,work,{true})})
            assertNull(index.getDownload("42"));assertFalse(f.original.exists())
        } finally {teardown(owner);f.provider.close()}
        val g=Fixture("43");val next=g.owner();val otherArt=DownloadArt(folders.newFolder(),barrier=g.barrier);val otherWork=coverWork(otherArt,g.barrier)
        try {
            seedOriginal(g);onWorker {next.prepare()};val unbound=onWorker {next.beginOriginalRetirement("43",true)}
            assertEquals(OfflineStore.SavedRemoval.Waiting,onWorker {next.recoverCompletedDelete("43",unbound.token,true,otherArt,otherWork,{true})})
            assertTrue(g.original.exists())
        } finally {teardown(next);g.provider.close()}
    }
    @Test fun publicDeleteRecoveryUsesExistingWorkerAndPostsOnlyConfirmedRemovalOnMain() {
        val n=SavedDeleteFixture();val f=n.f;val app=RuntimeEnvironment.getApplication()
        val field=OfflineStore::class.java.getDeclaredField("store").apply {isAccessible=true};val previous=field.get(OfflineStore)
        val mover=OfflineStore::class.java.getDeclaredField("mover").apply {isAccessible=true}.get(OfflineStore) as java.util.concurrent.ExecutorService
        val phone=Shelf.partitioned(f.source,MuonDownloadService::class.java);val card=Shelf.partitioned(f.target,MuonCardDownloadService::class.java)
        val removed=ArrayList<String>();card.partitionRemoved={assertSame(Looper.getMainLooper(),Looper.myLooper());removed+=it}
        val store=OfflineStore.Store(phone,n.art,PlayedSongEvictor(8192L) {},app.getSharedPreferences("partition-delete-recovery",0),f.f.provider,
            {},{},artwork=n.work,storageBarrier=f.f.barrier);store.card=card
        try {
            field.set(OfflineStore,store)
            assertFalse(OfflineStore.recoverDelete(app,true,f.key,n.pending.token,false));assertNotNull(n.row())
            assertTrue(OfflineStore.recoverDelete(app,true,f.key,n.pending.token,true));assertNotNull(DownloadMarks.moving)
            mover.submit {}.get(10,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf(f.key),removed);assertNull(n.row());assertNull(DownloadMarks.moving);assertTrue(f.f.barrier.quiescent)
            assertEquals(PartitionSavedDeletePhase.Complete,onWorker {f.target.deleteRecoveryPage().single().phase})
        } finally {
            mover.submit {}.get(10,TimeUnit.SECONDS);shadowOf(Looper.getMainLooper()).idle()
            field.set(OfflineStore,previous);DownloadMarks.moving=null;n.close()
        }
    }
    @Test fun deleteFinishRefusesActualOtherShelfReservedAllocationBeforeSourceMutation() {
        val n=SavedDeleteFixture();val f=n.f
        val request=DownloadRequest.Builder(f.key,Uri.parse("http://127.0.0.1:7814/other-owner")).setCustomCacheKey(f.key).build()
        try {
            f.source.prepareSave(request)
            assertEquals(OfflineStore.SavedRemoval.Failed,onWorker {f.target.completeSavedDelete(n.pending,true,n.art,n.work,{true},other=f.source)})
            assertTrue(n.allocation.exists());assertNotNull(n.row());assertTrue(f.f.barrier.quiescent)
            onWorker {f.source.abandonPrepared(request)}
        } finally {n.close()}
    }
    @Test fun deleteRecoverySummariesKeepOnlyFixedScalarsAndDisappearAfterActualCompletion() {
        val n=SavedDeleteFixture();val f=n.f
        try {
            val page=onWorker {f.target.pendingDeleteSummaries()}
            assertEquals(listOf(PartitionStorageRecoverySummary(f.key,n.pending.token,4L,PartitionRecoveryAction.Delete)),page.entries)
            assertEquals(f.key,page.after);assertFalse(page.hasMore)
            assertTrue(onWorker {f.target.pendingDeleteSummaries(f.key)}.entries.isEmpty())
            assertEquals(OfflineStore.SavedRemoval.Sent,n.finish())
            assertTrue(onWorker {f.target.pendingDeleteSummaries()}.entries.isEmpty())
        } finally {n.close()}
    }
    @Test fun originalMoveAdvancesDeleteSummaryCursorWithoutOfferingAnyDeleteAction() {
        val f=LocalPublicationFixture();val art=DownloadArt(folders.newFolder(),barrier=f.f.barrier);val work=coverWork(art,f.f.barrier)
        try {
            val copy=f.prepare();val completed=onWorker {f.source.publishCopiedOriginalTo(f.target,copy,true,{true},10)}
            onWorker {f.source.beginCoveredOriginalMove(f.target,copy,completed,true,art,work)}
            val page=onWorker {f.source.pendingDeleteSummaries()}
            assertTrue(page.entries.isEmpty());assertEquals("81",page.after);assertFalse(page.hasMore)
            assertNotNull(f.index.getDownload("81"));assertTrue(f.f.original.exists())
        } finally {f.close()}
    }
    @Test fun legacyFilesOnlyDeleteSummaryIsExplicitlyUnsupportedWhileBoundOriginalIsRecoverable() {
        val f=Fixture("42");seedOriginal(f);val first=f.owner()
        try {
            onWorker {first.prepare()};val bound=boundOriginalIntent(f,first)
            assertEquals(listOf(PartitionStorageRecoverySummary("42",bound.token,4L,PartitionRecoveryAction.Delete)),onWorker {first.pendingDeleteSummaries()}.entries)
        } finally {teardown(first);f.provider.close()}
        val g=Fixture("43");val next=g.owner()
        try {
            onWorker {next.prepare()};val pending=onWorker {next.beginOriginalRetirement("43",true)}
            assertEquals(listOf(PartitionStorageRecoverySummary("43",pending.token,4L,PartitionRecoveryAction.UnsupportedDelete)),onWorker {next.pendingDeleteSummaries()}.entries)
            assertTrue(g.original.exists())
        } finally {teardown(next);g.provider.close()}
    }
}
