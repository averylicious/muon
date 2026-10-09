@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionShelfServiceTest {
    @get:Rule val folders=TemporaryFolder()
    private val app get()=RuntimeEnvironment.getApplication()
    private val payload=byteArrayOf(1,4,1,4)
    private val storeField=OfflineStore::class.java.getDeclaredField("store").apply { isAccessible=true }
    private inner class Root(val name:String,val database:StandaloneDatabaseProvider,val budget:PartitionNativeBudget) {
        val legacy=folders.newFolder(); val root=File(folders.newFolder(),"partitions")
        val barrier=SavedStorageBarrier(); val original:File
        val owner:OwnedPartitionShelf
        init {
            val native=SimpleCache(legacy,NoOpCacheEvictor(),database)
            val db:File
            try {
                native.checkInitialization(); val hole=requireNotNull(native.startReadWrite("old-$name",0,4))
                try { original=native.startFile("old-$name",0,4); original.writeBytes(payload); native.commitFile(original,4) }
                finally { native.releaseHoleSpan(hole) }
                native.applyContentMetadataMutations("old-$name",ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
                db=File(database.readableDatabase.path)
            } finally { native.release() }
            owner=OwnedPartitionShelf(app,root,legacy,db,database,name,"fixture",{"fixture"},PartitionShelfStart.Fresh,
                barrier,{true},DataSource.Factory { ByteArrayDataSource(payload) },budget)
            owner.prepare()
        }
        fun closeFixture() {
            val startup=OwnedPartitionShelf::class.java.getDeclaredField("startup").apply { isAccessible=true }.get(owner)
            val made=StorageStartup::class.java.getDeclaredField("opened").apply { isAccessible=true }.get(startup)
            // TEST ONLY: concrete services have been destroyed and helpers cleared. Permit releasing
            // this exact fixture manager, never a production replacement/reset mechanism.
            val manager=made.javaClass.getDeclaredField("manager").apply { isAccessible=true }.get(made)
            manager.javaClass.getDeclaredField("attached").apply { isAccessible=true }.setBoolean(manager,false)
            for(name in listOf("manager","session","legacy","saves","migrations","catalog"))
                (made.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(made) as java.io.Closeable).close()
        }
    }
    private inner class Fixture(val withCard:Boolean=true):java.io.Closeable {
        val database=StandaloneDatabaseProvider(app)
        val budget=PartitionNativeBudget(4)
        val phoneRoot=Root("partition_phone",database,budget)
        val cardRoot=if(withCard) Root("partition_card",database,budget) else null
        val phone=Shelf.partitioned(phoneRoot.owner,MuonDownloadService::class.java)
        val card=cardRoot?.let { Shelf.partitioned(it.owner,MuonCardDownloadService::class.java) }
        val store=OfflineStore.Store(phone,DownloadArt(folders.newFolder()),PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("partition-service-test",Context.MODE_PRIVATE),database,{},{}).also { it.card=card }
        val previous=storeField.get(null)
        val destroy=ArrayList<()->Unit>()
        init {
            DownloadService.clearDownloadManagerHelpers(); storeField.set(null,store)
            listOfNotNull(phone,card).forEach { it.manager.setRequirements(Requirements(0)); it.manager.minRetryCount=0; await(it.manager) { true } }
        }
        fun service(card:Boolean=false):DownloadService {
            return if(card) Robolectric.buildService(MuonCardDownloadService::class.java).create().let {
                destroy+={it.destroy()}; it.get()
            } else Robolectric.buildService(MuonDownloadService::class.java).create().let {
                destroy+={it.destroy()}; it.get()
            }
        }
        fun preserved() {
            assertArrayEquals(payload,phoneRoot.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(phoneRoot.legacy))
            cardRoot?.let { assertArrayEquals(payload,it.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(it.legacy)) }
        }
        override fun close() {
            listOfNotNull(phone,card).forEach { await(it.manager) { true } }
            destroy.asReversed().forEach { it() }; DownloadService.clearDownloadManagerHelpers()
            storeField.set(null,previous); cardRoot?.closeFixture(); phoneRoot.closeFixture(); database.close()
        }
    }
    private fun await(manager:DownloadManager,condition:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(manager.isInitialized && manager.isIdle && condition()) return
            check(System.nanoTime()<end) { "Partition service manager did not settle" }; Thread.sleep(5)
        }
    }
    private fun request(id:String)=DownloadRequest.Builder("saved/$id",Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
        .setCustomCacheKey("saved/$id").setData(byteArrayOf(2,7)).build()
    private fun intent(shelf:Shelf,request:DownloadRequest,token:String)=
        DownloadService.buildAddDownloadIntent(app,shelf.service,request,false).putExtra(SAVE_DELIVERY_TOKEN,token)
    @Test fun actualPhoneAndCardServicesAcceptOnlyExactCurrentSaveReceiptsAndPreserveLegacyBytes() {
        Fixture().use { f ->
            for((n,shelf) in listOf(f.phone,requireNotNull(f.card)).withIndex()) {
                val service=f.service(card=n==1); val request=request("service-$n")
                var result:SaveDeliveryResult?=null; var covers=0
                val token=requireNotNull(f.store.saveDelivery.begin(shelf,listOf(request to "cover"),{_,_->covers++;true},{result=it})).single()
                val changed=request.copyWithId(request.id+"changed")
                service.onStartCommand(intent(shelf,changed,token),0,1); assertNull(result)
                service.onStartCommand(intent(shelf,request,token),0,2)
                await(shelf.manager) { shelf.completed(request.id) }
                assertEquals(SaveDeliveryResult(1,1,0,0),result); assertEquals(1,covers)
                assertTrue(shelf.holds(request.id)); assertTrue(shelf.audio.contains(request.id))
                service.onStartCommand(intent(shelf,request,token),0,3) // No replay or second cover.
                service.onStartCommand(DownloadService.buildRemoveDownloadIntent(app,shelf.service,request.id,false),0,4)
                await(shelf.manager) { true }; assertTrue(shelf.completed(request.id)); assertEquals(1,covers)
                val reader=shelf.savedSource.createDataSource()
                try {
                    assertEquals(4L,reader.open(DataSpec.Builder().setUri("muon-saved:test").setKey(request.id).build()))
                    val bytes=ByteArray(4); assertEquals(4,reader.read(bytes,0,4)); assertArrayEquals(payload,bytes)
                } finally { reader.close() }
                assertThrows(IOException::class.java) { shelf.cache }; f.preserved()
            }
        }
    }
    @Test fun actualServiceKeepsPinnedPrivateRestartForegroundObligationWithoutNewDownload() {
        Fixture(false).use { f ->
            val service=f.service()
            service.onStartCommand(Intent(app,f.phone.service).setAction("androidx.media3.exoplayer.downloadService.action.RESTART"),0,1)
            assertNotNull(shadowOf(service).lastForegroundNotification)
            assertTrue(f.phone.manager.currentDownloads.isEmpty()); f.preserved()
        }
    }
    @Test fun expiredUnclaimedServiceIntentReleasesOnlyPreparedReceiptAndNeverReplays() {
        Fixture(false).use { f ->
            val service=f.service(); val old=request("expired"); var result:SaveDeliveryResult?=null
            val token=requireNotNull(f.store.saveDelivery.begin(f.phone,listOf(old to "cover"),{_,_->fail("No expired cover");false},{result=it})).single()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SAVE_DELIVERY_TIMEOUT_MS))
            assertEquals(SaveDeliveryResult(1,0,0,1),result)
            assertFalse(f.phoneRoot.owner.abandonPrepared(old)) // Already retired; reservation stays.
            PartitionSaveJournal(f.phoneRoot.root,create=false).use { assertEquals(PartitionSavePhase.Reserved,it.find(old.id)?.phase) }
            service.onStartCommand(intent(f.phone,old,token),0,1); await(f.phone.manager) { true }
            assertFalse(f.phone.holds(old.id))
            val fresh=request("fresh"); result=null
            val next=requireNotNull(f.store.saveDelivery.begin(f.phone,listOf(fresh to "cover"),{_,_->true},{result=it})).single()
            service.onStartCommand(intent(f.phone,fresh,next),0,2); await(f.phone.manager) { f.phone.completed(fresh.id) }
            assertEquals(SaveDeliveryResult(1,1,0,0),result); f.preserved()
        }
    }
    @Test fun missingCardServiceFallbackRefusesSaveWithoutMutatingPhoneManager() {
        Fixture(false).use { f ->
            val wrong=f.service(card=true); val request=request("phone-only"); var result:SaveDeliveryResult?=null
            val token=requireNotNull(f.store.saveDelivery.begin(f.phone,listOf(request to "cover"),{_,_->true},{result=it})).single()
            wrong.onStartCommand(intent(f.phone,request,token),0,1)
            await(f.phone.manager) { true }; assertEquals(SaveDeliveryResult(1,0,0,0),result); assertFalse(f.phone.holds(request.id))
            val retry=request("phone-retry"); result=null
            val fresh=requireNotNull(f.store.saveDelivery.begin(f.phone,listOf(retry to "cover"),{_,_->true},{result=it})).single()
            f.service().onStartCommand(intent(f.phone,retry,fresh),0,2)
            await(f.phone.manager) { f.phone.completed(retry.id) }
            assertEquals(SaveDeliveryResult(1,1,0,0),result); f.preserved()
        }
    }
    @Test fun scalarExistingRowAndRawServiceRefusalNeverHydrateOrMutateOversizedSavedPayload() {
        Fixture(false).use { f ->
            val service=f.service(); val request=request("huge-retained")
            val index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(f.database,"partition_phone")
            index.putDownload(Download(request,Download.STATE_COMPLETED,1,2,4,Download.STOP_REASON_NONE,Download.FAILURE_REASON_NONE))
            val table=androidx.media3.database.DatabaseProvider.TABLE_PREFIX+"Downloadspartition_phone"
            f.database.writableDatabase.execSQL("UPDATE $table SET data=zeroblob(8388608) WHERE id=?",arrayOf(request.id))
            assertTrue(f.phone.holds(request.id)); assertTrue(f.phone.completed(request.id))
            assertNull(f.store.saveDelivery.begin(f.phone,listOf(request to "cover"),{_,_->fail("No retained cover");false},{}))
            service.onStartCommand(DownloadService.buildAddDownloadIntent(app,f.phone.service,request,false),0,1)
            service.onStartCommand(DownloadService.buildRemoveDownloadIntent(app,f.phone.service,request.id,false),0,2)
            await(f.phone.manager) { true }
            f.database.readableDatabase.rawQuery("SELECT state,length(data) FROM $table WHERE id=?",arrayOf(request.id)).use {
                assertTrue(it.moveToFirst()); assertEquals(Download.STATE_COMPLETED,it.getInt(0)); assertEquals(8388608,it.getInt(1))
            }
            PartitionSaveJournal(f.phoneRoot.root,create=false).use { assertNull(it.find(request.id)) }
            f.preserved()
        }
    }
    @Test fun exceptionAfterActualManagerAddRemainsUnconfirmedWithoutClaimingCoverOrDeletingBytes() {
        Fixture(false).use { f ->
            f.service(); val request=request("uncertain-return"); var result:SaveDeliveryResult?=null
            val token=requireNotNull(f.store.saveDelivery.begin(f.phone,listOf(request to "cover"),{_,_->fail("No confirmed cover");false},{result=it})).single()
            assertThrows(IOException::class.java) {
                OfflineStore.deliverCommand(app,intent(f.phone,request,token),f.phone) {
                    f.phone.manager.addDownload(request); throw IOException("Injected return failure after actual Add")
                }
            }
            await(f.phone.manager) { f.phone.completed(request.id) }
            assertEquals(SaveDeliveryResult(1,0,0,1),result); assertTrue(f.phone.audio.contains(request.id)); f.preserved()
        }
    }
}
