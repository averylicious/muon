@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.os.Looper
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
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
class PartitionStorePreparationTest {
    @get:Rule val folders=TemporaryFolder()
    private val app get()=RuntimeEnvironment.getApplication()
    private val bytes=byteArrayOf(1,3,5,7)
    private inner class Fixture {
        val seed=StandaloneDatabaseProvider(app)
        val phoneLegacy=folders.newFolder(); val cardLegacy=folders.newFolder()
        val phoneRoot=File(folders.newFolder(),"phone"); val cardRoot=File(folders.newFolder(),"card")
        val originals=ArrayList<File>()
        init {
            for((folder,key) in listOf(phoneLegacy to "old-phone",cardLegacy to "old-card")) {
                val cache=SimpleCache(folder,NoOpCacheEvictor(),seed)
                try {
                    cache.checkInitialization(); val span=requireNotNull(cache.startReadWrite(key,0,4))
                    try { val file=cache.startFile(key,0,4); file.writeBytes(bytes); cache.commitFile(file,4); originals+=file }
                    finally { cache.releaseHoleSpan(span) }
                    cache.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
                } finally { cache.release() }
            }
        }
        fun plan(card:Boolean=true)=PartitionStorePlan(
            PartitionRootPlan(phoneRoot,phoneLegacy,"phone-volume",{"phone-volume"},PartitionShelfStart.Fresh),
            if(card) PartitionRootPlan(cardRoot,cardLegacy,"card-volume",{"card-volume"},PartitionShelfStart.Fresh) else null)
    }
    private fun <T> worker(action:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try { return try { executor.submit<T> { action() }.get(10,TimeUnit.SECONDS) }
            catch(error:java.util.concurrent.ExecutionException) { throw requireNotNull(error.cause) } }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)) }
    }
    private fun await(vararg managers:DownloadManager) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(managers.all { it.isInitialized && it.isIdle }) return
            check(System.nanoTime()<end); Thread.sleep(5)
        }
    }
    private fun request(key:String)=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/file")).setCustomCacheKey(key).build()
    private fun resources(root:OwnedPartitionShelf):Any? {
        val state=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply { isAccessible=true }.get(root)
        return StorageStartup::class.java.getDeclaredField("opened").apply { isAccessible=true }.get(state)
    }
    /** Fixture-only release of proven idle resources. No production reset/shutdown authority. */
    private fun cleanup(startup:StorageStartup<OfflineStore.PreparedStorage>,failedBeforeBinding:Boolean=false) {
        val handles=StorageStartup::class.java.getDeclaredField("handles").apply { isAccessible=true }.get(startup) as Array<*>
        for(root in handles.filterIsInstance<OwnedPartitionShelf>()) {
            val made=resources(root) ?: continue
            fun field(name:String)=made.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(made)
            val executor=field("workers") as java.util.concurrent.ExecutorService
            executor.shutdown(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))
            if(!failedBeforeBinding) {
                for(name in listOf("manager","session","legacy","saves","migrations","catalog")) (field(name) as java.io.Closeable).close()
            } else {
                // This fixture failed before any manager/native target/task began. Its sticky gate
                // intentionally has no reset. Close only the known raw readonly DB/journal handles.
                assertEquals(0,PartitionNativeBudget.process.resident)
                val legacy=field("legacy")
                val index=LegacyTransitionOwner::class.java.getDeclaredField("index").apply { isAccessible=true }.get(legacy)
                (index as? android.database.sqlite.SQLiteDatabase)?.close()
                for(name in listOf("saves","migrations","catalog")) (field(name) as java.io.Closeable).close()
            }
        }
        for(root in handles.filterIsInstance<OwnedPartitionShelf>()) {
            resources(root)?.let {made -> worker {
                for(name in listOf("retirements","moves","deletes"))
                    (made.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(made) as java.io.Closeable).close()
            }}
        }
        for(handle in handles) when(handle) {
            is java.util.concurrent.ExecutorService -> { handle.shutdown(); assertTrue(handle.awaitTermination(10,TimeUnit.SECONDS)) }
            is StandaloneDatabaseProvider -> handle.close()
            is PlayedClaims -> (PlayedClaims::class.java.getDeclaredField("disk").apply { isAccessible=true }.get(handle) as? java.io.Closeable)?.close()
            is DiskPlayedSpanOrder -> handle.close()
            is DownloadByteTotals -> handle.close()
            is DownloadMarkLedger -> handle.close()
        }
    }
    @Test fun actualAppPreparerOwnsBothShelvesWithoutOpeningLegacyNativeCaches() {
        val f=Fixture(); val startup=StorageStartup<OfflineStore.PreparedStorage>()
        try {
            val ready=worker { startup.open { OfflineStore.preparePartition(app,it,f.plan()) } }
            assertNull(ready.phone.cache); assertNull(ready.card!!.cache)
            val phone=requireNotNull(ready.phone.partition); val card=requireNotNull(ready.card!!.partition)
            assertSame(ready.barrier,phone.storageBarrier); assertSame(ready.barrier,card.storageBarrier)
            assertEquals(MuonDownloadService::class.java,ready.phone.service)
            assertEquals(MuonCardDownloadService::class.java,ready.card!!.service)
            assertEquals(0,PartitionNativeBudget.process.resident)
            val phoneShelf=Shelf.partitioned(phone,ready.phone.service); val cardShelf=Shelf.partitioned(card,ready.card!!.service)
            await(phoneShelf.manager,cardShelf.manager)
            assertSame(phoneShelf.manager,phone.initializeManager()); assertSame(cardShelf.manager,card.initializeManager())
            worker {
                for((owner,key) in listOf(phone to "old-phone",card to "old-card")) {
                    val reader=owner.audio.source.createDataSource()
                    try {
                        assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:fixture").setKey(key).build()))
                        val read=ByteArray(4); assertEquals(4,reader.read(read,0,4)); assertArrayEquals(bytes,read)
                    } finally { reader.close() }
                }
            }
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy)); assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
            f.originals.forEach { assertArrayEquals(bytes,it.readBytes()) }
        } finally { cleanup(startup); f.seed.close() }
    }
    @Test fun anotherShelfsUnstartedJournalClaimRefusesANameBeforeNativeAllocation() {
        val f=Fixture(); val startup=StorageStartup<OfflineStore.PreparedStorage>(); val name="saved/shared-name"
        try {
            val ready=worker { startup.open { OfflineStore.preparePartition(app,it,f.plan()) } }
            val phone=requireNotNull(ready.phone.partition); val card=requireNotNull(ready.card!!.partition)
            await(phone.initializeManager(),card.initializeManager())
            worker {
                card.prepareSave(request(name))
                assertTrue(card.claimsName(name))
                assertThrows(IOException::class.java) { phone.prepareSave(request(name)) }
                assertTrue(phone.saveRecoveryPage().isEmpty())
                assertEquals(PartitionSavePhase.Reserved,card.saveRecoveryPage().single().phase)
            }
            assertEquals(0,PartitionNativeBudget.process.resident)
            f.originals.forEach { assertArrayEquals(bytes,it.readBytes()) }
        } finally { cleanup(startup); f.seed.close() }
    }
    @Test fun absentCardStillProtectsItsRetainedAliasWithoutReadingOversizedData() {
        val f=Fixture(); val startup=StorageStartup<OfflineStore.PreparedStorage>(); val name="saved/absent-card-claim"
        try {
            val index=DefaultDownloadIndex(f.seed,"card")
            index.putDownload(Download(request(name),Download.STATE_COMPLETED,1,2,4,0,0))
            f.seed.writableDatabase.execSQL("UPDATE ExoPlayerDownloadscard SET data=zeroblob(8388608)")
            val ready=worker { startup.open { OfflineStore.preparePartition(app,it,f.plan(card=false)) } }
            val phone=requireNotNull(ready.phone.partition); await(phone.initializeManager())
            worker {
                assertThrows(IOException::class.java) { phone.prepareSave(request(name)) }
                assertTrue(phone.saveRecoveryPage().isEmpty())
            }
            f.seed.readableDatabase.rawQuery("SELECT length(data) FROM ExoPlayerDownloadscard",null).use {
                assertTrue(it.moveToFirst()); assertEquals(8388608L,it.getLong(0))
            }
            assertEquals(0,PartitionNativeBudget.process.resident); assertFalse(f.phoneRoot.resolve("resources").exists())
        } finally { cleanup(startup); f.seed.close() }
    }
    @Test fun unavailableOverlappingAndMainThreadPlansRefuseBeforeAllocatingParticipants() {
        val f=Fixture()
        try {
            val good=f.plan(); val wrong=good.copy(phone=good.phone.copy(currentVolume={"changed-volume"}))
            val overlap=good.copy(card=good.phone.copy(volume="card",currentVolume={"card"}))
            for(plan in listOf(wrong,overlap)) {
                val startup=StorageStartup<OfflineStore.PreparedStorage>()
                assertThrows(IOException::class.java) { worker { startup.open { OfflineStore.preparePartition(app,it,plan) } } }
                assertEquals(0,startup.retained)
                assertThrows(IOException::class.java) { worker { startup.open { OfflineStore.preparePartition(app,it,good) } } }
                assertEquals(0,startup.retained)
            }
            val main=StorageStartup<OfflineStore.PreparedStorage>()
            assertThrows(IllegalStateException::class.java) { main.open { OfflineStore.preparePartition(app,it,good) } }
            assertEquals(0,main.retained); assertFalse(f.phoneRoot.exists()); assertFalse(f.cardRoot.exists())
        } finally { f.seed.close() }
    }
    @Test fun partialCardFailureKeepsTheActualPhoneOwnerAndRefusesEveryFallbackOrRetry() {
        val f=Fixture(); val startup=StorageStartup<OfflineStore.PreparedStorage>()
        try {
            val invalid=f.plan().copy(card=f.plan().card!!.copy(legacyDirectory=folders.newFolder()))
            assertThrows(IOException::class.java) { worker { startup.open { OfflineStore.preparePartition(app,it,invalid) } } }
            assertTrue(f.phoneRoot.isDirectory); assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy))
            assertTrue(startup.retained>0)
            assertThrows(IOException::class.java) { worker { startup.open { OfflineStore.preparePartition(app,it,f.plan()) } } }
            assertEquals(0,PartitionNativeBudget.process.resident)
            f.originals.forEach { assertArrayEquals(bytes,it.readBytes()) }
        } finally { cleanup(startup,failedBeforeBinding=true); f.seed.close() }
    }
    @Test fun commonAppBinderBootstrapsExactManagersAndPartitionUsageWithoutReplacingPreparedOwners() {
        val f=Fixture(); val startup=StorageStartup<OfflineStore.PreparedStorage>(); val bound=StorageStartup<OfflineStore.Store>()
        val previousUsed=PlayedCacheState.used; val previousLimit=PlayedCacheState.limit
        try {
            val key=playedKey("binder-legacy"); val cache=SimpleCache(f.phoneLegacy,NoOpCacheEvictor(),f.seed)
            try {
                cache.checkInitialization(); val span=requireNotNull(cache.startReadWrite(key,0,4))
                try { val file=cache.startFile(key,0,4); file.writeBytes(bytes); cache.commitFile(file,4); f.originals+=file }
                finally { cache.releaseHoleSpan(span) }
                cache.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
            } finally { cache.release() }
            val ready=worker { startup.open { OfflineStore.preparePartition(app,it,f.plan()) } }
            PlayedCacheState.used=-1
            val store=bound.open { OfflineStore.bindStorage(ready,it) }
            assertSame(ready.phone.partition,store.phone.partition); assertSame(ready.card!!.partition,store.card!!.partition)
            await(store.phone.manager,store.card!!.manager)
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(store.bootstrapPending!=0 || !store.playedClaims.known || PlayedCacheState.used!=4L) {
                shadowOf(Looper.getMainLooper()).idle()
                check(System.nanoTime()<end) { "Common partition bootstrap/usage did not settle" }; Thread.sleep(5)
            }
            assertFalse(store.bootstrapFailed); assertTrue(store.storageBarrier.quiescent)
            assertSame(store.phone.manager,requireNotNull(ready.phone.partition).initializeManager())
            assertSame(store.card!!.manager,requireNotNull(ready.card!!.partition).initializeManager())
            assertSame(store,bound.open { error("Must not bind another manager") })
            val wrongLooper=StorageStartup<OfflineStore.Store>()
            assertThrows(IllegalStateException::class.java) { worker { wrongLooper.open { OfflineStore.bindStorage(ready,it) } } }
            assertEquals(0,wrongLooper.retained)
            f.originals.forEach { assertArrayEquals(bytes,it.readBytes()) }
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy)); assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
        } finally {
            cleanup(startup); f.seed.close(); PlayedCacheState.used=previousUsed; PlayedCacheState.limit=previousLimit
        }
    }

    @Test fun legacyAppStartupRejectsUnknownDownloadSchemaBeforeNativeCacheOrMedia3RowReset() {
        val f=Fixture(); val startup=StorageStartup<OfflineStore.PreparedStorage>(); val name="saved/legacy-schema"
        try {
            DefaultDownloadIndex(f.seed).putDownload(Download(request(name),Download.STATE_COMPLETED,1,2,4,0,0))
            f.seed.writableDatabase.execSQL("UPDATE ExoPlayerDownloads SET data=zeroblob(8388608)")
            androidx.media3.database.VersionTable.setVersion(f.seed.writableDatabase,
                androidx.media3.database.VersionTable.FEATURE_OFFLINE,"",77)
            assertThrows(IOException::class.java) { worker { startup.open { OfflineStore.prepareLegacy(app,it) } } }
            f.seed.readableDatabase.rawQuery("SELECT length(data) FROM ExoPlayerDownloads WHERE id=?",arrayOf(name)).use {
                assertTrue(it.moveToFirst()); assertEquals(8388608L,it.getLong(0))
            }
            assertEquals(77,androidx.media3.database.VersionTable.getVersion(f.seed.readableDatabase,
                androidx.media3.database.VersionTable.FEATURE_OFFLINE,""))
            assertFalse(SimpleCache.isCacheFolderLocked(File(app.filesDir,"downloads")))
            f.originals.forEach { assertArrayEquals(bytes,it.readBytes()) }
        } finally { cleanup(startup); f.seed.close() }
    }

    @Test fun actualPersistedPreparerMarksResumeAfterBothRealRootsBeforeManagerBindingAndRestarts() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        val settings=PartitionStartupSettings(prefs);val startup=StorageStartup<OfflineStore.PreparedStorage>()
        var firstClosed=false;var next:StorageStartup<OfflineStore.PreparedStorage>?=null
        try {
            settings.request(f.plan(),true)
            val ready=worker {startup.open {OfflineStore.preparePersistedPartition(app,it,f.plan())}}
            val choice=settings.read();assertEquals(PartitionStartupPhase.Ready,choice.phase);assertEquals(PartitionShelfStart.Resume,choice.start)
            assertNotNull(ready.phone.partition);assertNotNull(ready.card!!.partition);assertNull(ready.phone.cache)
            assertTrue(f.phoneRoot.isDirectory);assertTrue(f.cardRoot.isDirectory);assertEquals(0,PartitionNativeBudget.process.resident)
            cleanup(startup);firstClosed=true
            val resumed=StorageStartup<OfflineStore.PreparedStorage>();next=resumed
            val fresh=f.plan();val plan=fresh.copy(phone=fresh.phone.copy(start=PartitionShelfStart.Resume),card=fresh.card!!.copy(start=PartitionShelfStart.Resume))
            val reopened=worker {resumed.open {OfflineStore.preparePersistedPartition(app,it,plan)}}
            assertNotNull(reopened.phone.partition);assertNotNull(reopened.card!!.partition);assertEquals(choice,settings.read())
            f.originals.forEach {assertArrayEquals(bytes,it.readBytes())}
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy));assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
        } finally {
            next?.let(::cleanup);if(!firstClosed) cleanup(startup);f.seed.close()
            prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        }
    }
    @Test fun interruptedPersistedSetupRefusesBeforeAnyProviderOrRootAndStaysSticky() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        val settings=PartitionStartupSettings(prefs);val choice=settings.request(f.plan(),true);val pending=settings.begin(choice,f.plan())
        val before=app.databaseList().toSet();val startup=StorageStartup<OfflineStore.PreparedStorage>()
        try {
            assertThrows(IOException::class.java) {worker {startup.open {OfflineStore.preparePersistedPartition(app,it,f.plan())}}}
            assertEquals(1,startup.retained);assertEquals(before,app.databaseList().toSet())
            assertFalse(f.phoneRoot.exists());assertFalse(f.cardRoot.exists());assertEquals(pending,settings.read())
            settings.reviewResume(pending,true)
            assertThrows(IOException::class.java) {worker {startup.open {OfflineStore.preparePersistedPartition(app,it,f.plan())}}}
            f.originals.forEach {assertArrayEquals(bytes,it.readBytes())}
        } finally {
            cleanup(startup);f.seed.close();prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        }
    }

    @Test fun readyAbsentCardKeepsPhoneReadableAndRefusesWritesDespiteEmptyCardDownloadIndex() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        val settings=PartitionStartupSettings(prefs);val initial=StorageStartup<OfflineStore.PreparedStorage>()
        var firstClosed=false;var resumed:StorageStartup<OfflineStore.PreparedStorage>?=null
        try {
            settings.request(f.plan(),true)
            val first=worker {initial.open {OfflineStore.preparePersistedPartition(app,it,f.plan())}}
            val hidden="saved/card-reservation-without-download-row"
            DefaultDownloadIndex(first.database).getDownloads().use {assertFalse(it.moveToNext())}
            DefaultDownloadIndex(first.database,"card").getDownloads().use {assertFalse(it.moveToNext())}
            worker {requireNotNull(first.card!!.partition).prepareSave(request(hidden))}
            assertFalse(PartitionCompletionIndex(first.database,"card").holdsNameIfPresent(hidden))
            val receipt=settings.read()
            cleanup(initial);firstClosed=true
            val cardBefore=f.cardRoot.walkTopDown().filter {it.isFile}.associate {it.relativeTo(f.cardRoot).path to it.readBytes().toList()}
            val fresh=f.plan();val absent=fresh.copy(phone=fresh.phone.copy(start=PartitionShelfStart.Resume),card=null,missingCardVolume="card-volume")
            val next=StorageStartup<OfflineStore.PreparedStorage>();resumed=next
            val ready=worker {next.open {OfflineStore.preparePersistedPartition(app,it,absent)}}
            assertNull(ready.card);assertEquals("card-volume",ready.missingPartitionCardVolume)
            assertEquals(receipt,settings.read());val phone=requireNotNull(ready.phone.partition)
            worker {
                val audio=phone.audio.source.createDataSource()
                try {
                    assertEquals(4L,audio.open(DataSpec.Builder().setUri("muon-saved:fixture").setKey("old-phone").build()))
                    val read=ByteArray(4);assertEquals(4,audio.read(read,0,4));assertArrayEquals(bytes,read)
                } finally {audio.close()}
                for(key in listOf(hidden,"saved/new-while-card-absent"))
                    assertThrows(IOException::class.java) {phone.prepareSave(request(key))}
                assertFalse(phone.freshMigrationAvailable("old-phone"))
                assertThrows(IOException::class.java) {phone.migrate(CacheMigrationControl(30000),"old-phone",{Long.MAX_VALUE},{})}
                assertThrows(IOException::class.java) {phone.beginOriginalRetirement("old-phone",true)}
                assertEquals(OfflineStore.SavedRemoval.Unavailable,phone.removeCompleted("old-phone") {true})
                assertTrue(phone.saveRecoveryPage().isEmpty());assertTrue(phone.retirementRecoveryPage().isEmpty())
                assertTrue(phone.pendingMigrationSummaries().entries.isEmpty())
            }
            assertEquals(cardBefore,f.cardRoot.walkTopDown().filter {it.isFile}.associate {it.relativeTo(f.cardRoot).path to it.readBytes().toList()})
            assertTrue(ready.barrier.quiescent);f.originals.forEach {assertArrayEquals(bytes,it.readBytes())}
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy));assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
        } finally {
            resumed?.let(::cleanup);if(!firstClosed) cleanup(initial);f.seed.close()
            prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        }
    }

    @Test fun disabledSelectedStartupRefusesPartitionBeforeAnyProviderOrLegacyNativeFallback() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        val settings=PartitionStartupSettings(prefs);val choice=settings.request(f.plan(),true)
        val startup=StorageStartup<OfflineStore.PreparedStorage>();val before=app.databaseList().toSet()
        try {
            assertFalse(PARTITION_OPT_IN_AVAILABLE)
            assertThrows(IOException::class.java) {worker {startup.open {OfflineStore.prepareSelectedStorage(app,it)}}}
            assertEquals(0,startup.retained);assertEquals(before,app.databaseList().toSet());assertEquals(choice,settings.read())
            assertFalse(f.phoneRoot.exists());assertFalse(f.cardRoot.exists())
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy));assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
            f.originals.forEach {assertArrayEquals(bytes,it.readBytes())}
        } finally {cleanup(startup);f.seed.close();prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()}
    }
    @Test fun explicitlyEnabledSelectorOwnsActualPartitionRootsAndDoesNotConstructLegacyNativeCache() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()
        val settings=PartitionStartupSettings(prefs);settings.request(f.plan(),true)
        val startup=StorageStartup<OfflineStore.PreparedStorage>()
        try {
            val ready=worker {startup.open {OfflineStore.prepareSelectedStorage(app,it,partitionEnabled=true,resolve={f.plan()})}}
            assertNull(ready.phone.cache);assertNull(ready.card!!.cache);assertNotNull(ready.phone.partition)
            assertEquals(PartitionStartupPhase.Ready,settings.read().phase);assertEquals(0,PartitionNativeBudget.process.resident)
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy));assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
            f.originals.forEach {assertArrayEquals(bytes,it.readBytes())}
        } finally {cleanup(startup);f.seed.close();prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit()}
    }

    @Test fun firstReadOnlyReviewComposesWithActualSelectedStartupBindingAndReadyRestart() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit())
        val reviewGate=StorageStartupAdmission();assertTrue(reviewGate.review())
        val review=PartitionStartupReviewOwner(reviewGate)
        val first=StorageStartup<OfflineStore.PreparedStorage>();var firstClosed=false
        val next=StorageStartup<OfflineStore.PreparedStorage>();var nextStarted=false
        val previousUsed=PlayedCacheState.used;val previousLimit=PlayedCacheState.limit
        try {
            DefaultDownloadIndex(f.seed).putDownload(Download(request("old-phone"),Download.STATE_COMPLETED,1,2,4,0,0))
            DefaultDownloadIndex(f.seed,"card").putDownload(Download(request("old-card"),Download.STATE_COMPLETED,1,2,4,0,0))
            val chosen=worker {review.request(f.plan(),File(f.seed.readableDatabase.path),true) {PartitionStartupSettings(prefs)}}
            assertEquals(PartitionOptInPhase.RestartRequired,review.phase)
            assertFalse(f.phoneRoot.exists());assertFalse(f.cardRoot.exists())
            assertThrows(IOException::class.java) {reviewGate.normal()}
            // Fresh process owners only; no reset or reuse of the review admission/startup.
            val normal=StorageStartupAdmission();normal.normal();assertFalse(normal.review())
            val ready=worker {first.open {OfflineStore.prepareSelectedStorage(app,it,partitionEnabled=true,resolve={choice ->
                assertEquals(chosen,choice);f.plan()
            })}}
            val bound=StorageStartup<OfflineStore.Store>();val store=bound.open {OfflineStore.bindStorage(ready,it)}
            await(store.phone.manager,store.card!!.manager)
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(store.bootstrapPending!=0 || !store.playedClaims.known) {
                shadowOf(Looper.getMainLooper()).idle();check(System.nanoTime()<end);Thread.sleep(5)
            }
            assertFalse(store.bootstrapFailed);assertEquals(PartitionStartupPhase.Ready,PartitionStartupSettings(prefs).read().phase)
            assertTrue(store.storageBarrier.quiescent);assertNull(ready.phone.cache);assertNull(ready.card!!.cache)
            worker {
                val phone=requireNotNull(store.phone.partition)
                assertTrue(phone.freshMigrationAvailable("old-phone"))
                val optimized=phone.migrate(CacheMigrationControl(30000),"old-phone",{Long.MAX_VALUE},{})
                assertEquals(MigrationPhase.Ready,optimized.phase)
            }
            cleanup(first);firstClosed=true
            nextStarted=true
            val fresh=f.plan();val resume=fresh.copy(phone=fresh.phone.copy(start=PartitionShelfStart.Resume),card=fresh.card!!.copy(start=PartitionShelfStart.Resume))
            val restored=worker {next.open {OfflineStore.prepareSelectedStorage(app,it,partitionEnabled=true,resolve={choice ->
                assertEquals(PartitionStartupPhase.Ready,choice.phase);resume
            })}}
            worker {
                val phone=requireNotNull(restored.phone.partition)
                assertEquals(MigrationPhase.Ready,phone.migrationRecoveryPage().single().phase)
                assertFalse(phone.freshMigrationAvailable("old-phone"))
                val reader=phone.audio.source.createDataSource()
                try {
                    assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:restart").setKey("old-phone").build()))
                    val read=ByteArray(4);assertEquals(4,reader.read(read,0,4));assertArrayEquals(bytes,read)
                } finally {reader.close()}
            }
            f.originals.forEach {assertArrayEquals(bytes,it.readBytes())}
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy));assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
        } finally {
            if(nextStarted) cleanup(next);if(!firstClosed) cleanup(first);f.seed.close()
            PlayedCacheState.used=previousUsed;PlayedCacheState.limit=previousLimit
            assertTrue(prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit())
        }
    }

    @Test fun explicitInterruptedSetupReusesPublishedPhoneAndCreatesOnlyUnpublishedCardRoot() {
        val f=Fixture();val prefs=app.getSharedPreferences("storage",android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit())
        val settings=PartitionStartupSettings(prefs);val chosen=settings.request(f.plan(),true)
        settings.begin(chosen,f.plan())
        worker {PartitionBootstrapOwner(f.phoneRoot,SavedStorageBarrier(),{true}).publish()}
        val phoneBefore=f.phoneRoot.listFiles()!!.associate {it.name to it.readBytes().toList()}
        val interrupted=File(f.cardRoot.parentFile,".${f.cardRoot.name}.setup-interrupted").apply {assertTrue(mkdir())}
        val evidence=File(interrupted,"new-save-journal-v1.db").apply {writeBytes(byteArrayOf(11,22,33))}
        val reviewed=PartitionSetupResumeOwner().request(true) {PartitionStartupSettings(prefs)}
        assertEquals(PartitionShelfStart.ReviewedSetup,reviewed.start)
        val plan=f.plan().let {it.copy(phone=it.phone.copy(start=reviewed.start),card=it.card!!.copy(start=reviewed.start))}
        val startup=StorageStartup<OfflineStore.PreparedStorage>()
        try {
            val ready=worker {startup.open {OfflineStore.prepareSelectedStorage(app,it,partitionEnabled=true,resolve={plan})}}
            assertNotNull(ready.phone.partition);assertNotNull(ready.card!!.partition)
            assertEquals(PartitionStartupPhase.Ready,settings.read().phase);assertEquals(PartitionShelfStart.Resume,settings.read().start)
            assertArrayEquals(byteArrayOf(11,22,33),evidence.readBytes())
            assertEquals(phoneBefore,f.phoneRoot.listFiles()!!.associate {it.name to it.readBytes().toList()})
            f.originals.forEach {assertArrayEquals(bytes,it.readBytes())};assertEquals(0,PartitionNativeBudget.process.resident)
            assertFalse(SimpleCache.isCacheFolderLocked(f.phoneLegacy));assertFalse(SimpleCache.isCacheFolderLocked(f.cardLegacy))
        } finally {cleanup(startup);f.seed.close();assertTrue(prefs.edit().remove(StorageBackendSelection.KEY).remove(PartitionStartupSettings.KEY).commit())}
    }

}
