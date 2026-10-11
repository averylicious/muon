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
    private inner class Fixture {
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val legacy=folders.newFolder(); val parent=folders.newFolder(); val root=File(parent,"partition-shelf")
        val databaseFile:File; val original:File
        val barrier=SavedStorageBarrier(); val budget=PartitionNativeBudget(2); var volume:String?="fixture"
        init {
            val native=SimpleCache(legacy,NoOpCacheEvictor(),provider)
            try {
                native.checkInitialization(); val hole=requireNotNull(native.startReadWrite("old",0,4))
                try { original=native.startFile("old",0,4); original.writeBytes(payload); native.commitFile(original,4) }
                finally { native.releaseHoleSpan(hole) }
                native.applyContentMetadataMutations("old",ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
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
    }
    @Test fun completeShelfStartsWithoutLegacyNativeCacheAndRoutesOriginalMigratedAndNewSavedBytes() {
        val f=Fixture(); val owner=f.owner()
        try {
            owner.prepare(); owner.prepare(); assertEquals(0,f.budget.resident)
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
            owner.prepare()
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
            owner.prepare()
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
            owner.prepare()
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
            owner.prepare()
            val rejected=PartitionMigrationWork(java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException() },
                {control,key -> owner.migrate(control,key,{Long.MAX_VALUE},{}) },{owner.isAvailable},nanoTime={0L})
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
    @Test fun resumedShelfReadsReadyCopyWithoutReopeningLegacyCacheOrInventingNewMigration() {
        val f=Fixture(); val first=f.owner(); var resumed:OwnedPartitionShelf?=null; var firstClosed=false
        try {
            first.prepare()
            val record=first.migrate(CacheMigrationControl(1000,nanoTime={0L}),"old",{Long.MAX_VALUE},{})
            teardown(first); firstClosed=true; assertEquals(0,f.budget.resident)
            val next=f.owner(PartitionShelfStart.Resume); resumed=next; next.prepare()
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
            first.prepare(); teardown(first); firstClosed=true
            val file=File(f.root,"partition-locators-v1.db"); val kept=File(f.root,"preserved-locators.db")
            var observations=0
            val resume=f.owner(PartitionShelfStart.Resume,currentVolume={
                observations++
                if(observations==2) { check(file.renameTo(kept)); kept.copyTo(file) }
                f.volume
            })
            assertThrows(IOException::class.java) { resume.prepare() }
            assertTrue(kept.isFile); assertTrue(file.isFile); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertArrayEquals(payload,f.original.readBytes()); assertEquals(0,f.budget.resident)
            assertThrows(IOException::class.java) { resume.prepare() }
        } finally { if(!firstClosed) teardown(first); f.provider.close() }
    }
    @Test fun resumeNeverCreatesAMissingJournalAndFreshNeverAdoptsExistingRoot() {
        val f=Fixture(); assertTrue(f.root.mkdir()); val marker=File(f.root,"user-original").apply { writeText("kept") }
        try {
            val fresh=f.owner(); assertThrows(IOException::class.java) { fresh.prepare() }
            assertEquals("kept",marker.readText()); assertArrayEquals(payload,f.original.readBytes())
            val resume=f.owner(PartitionShelfStart.Resume)
            assertThrows(IOException::class.java) { resume.prepare() }
            assertFalse(File(f.root,"new-save-journal-v1.db").exists())
            assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
        } finally { f.provider.close() }
    }
    @Test fun partitionRootCannotAliasTheLegacyFolder() {
        val f=Fixture(); val owner=f.owner(path=f.legacy)
        try {
            assertThrows(IOException::class.java) { owner.prepare() }
            assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.legacy))
            assertEquals(0,f.budget.resident)
        } finally { f.provider.close() }
    }
}
