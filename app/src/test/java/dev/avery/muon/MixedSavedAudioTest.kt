@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File
import java.io.IOException
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MixedSavedAudioTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val fixtures=mutableListOf<Fixture>()
    private inner class Fixture {
        val root=folders.newFolder()
        val catalog=CachePartitionCatalog(root)
        val journal=CacheMigrationJournal(root)
        val saves=PartitionSaveJournal(root,create=true)
        val budget=PartitionNativeBudget(3)
        var volume:String?="card"
        val owner=PartitionNativeOwner(catalog,journal,"card",{volume},budget=budget,saves=saves)
        val native=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { it.checkInitialization() }
        val legacy=LegacySavedAudio(native)
        val indexName="mixed_"+fixtures.size
        val index=DefaultDownloadIndex(database,indexName).also { it.getDownloads().close() }
        val published=PartitionPublishedAudio(owner,capacity=2)
        val completed=PartitionCompletedAudio(owner,database,indexName,capacity=2)
        val audio=MixedSavedAudio(owner,legacy,published,completed)
    }
    private fun fixture()=Fixture().also(fixtures::add)
    @After fun close() {
        try { fixtures.asReversed().forEach { it.published.close(); it.completed.close(); it.native.release(); it.saves.close(); it.journal.close(); it.catalog.close() } }
        finally { database.close() }
    }
    private fun seed(cache:Cache,key:String,bytes:ByteArray) {
        val hole=requireNotNull(cache.startReadWrite(key,0,bytes.size.toLong()))
        try { val file=cache.startFile(key,0,bytes.size.toLong()); file.writeBytes(bytes); cache.commitFile(file,bytes.size.toLong()) }
        finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),bytes.size.toLong()))
    }
    private fun published(f:Fixture,key:String,bytes:ByteArray) {
        seed(f.native,key,bytes)
        CacheMigrationPublication(f.catalog,f.journal,f.owner.migrationTarget(key)).migrate(f.native,key,{})
    }
    private fun completed(f:Fixture,key:String,bytes:ByteArray) {
        val ticket=f.saves.begin(f.catalog.reserve(key))
        f.owner.openNewSave(ticket).let { seed(it,key,bytes); it.release() }
        val request=DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/file/1")).setCustomCacheKey(key).build()
        f.index.putDownload(Download(request,Download.STATE_COMPLETED,1,2,bytes.size.toLong(),0,Download.FAILURE_REASON_NONE))
    }
    private fun spec(key:String)=DataSpec.Builder().setUri("muon-saved:fixture").setKey(key).build()
    private fun read(f:Fixture,key:String):ByteArray {
        val source=f.audio.source.createDataSource()
        try {
            val length=source.open(spec(key)).toInt(); val bytes=ByteArray(length); var offset=0
            while(offset<length) { val n=source.read(bytes,offset,length-offset); assertTrue(n>0); offset+=n }
            assertEquals(-1,source.read(ByteArray(1),0,1)); return bytes
        } finally { source.close() }
    }
    @Test fun actualMixedPublishedReaderKeepsSharedBarrierThroughEofAndCleanClose() {
        val f=fixture(); published(f,"barrier",byteArrayOf(4,5))
        val barrier=SavedStorageBarrier(); val audio=BarrierSavedAudio(f.audio,barrier)
        val reader=audio.source.createDataSource(); reader.open(spec("barrier"))
        assertEquals(2,reader.read(ByteArray(2),0,2)); assertEquals(-1,reader.read(ByteArray(1),0,1))
        assertEquals(1,f.published.active); assertEquals(1,barrier.active)
        assertThrows(IOException::class.java) { barrier.exclusive() }
        reader.close(); assertEquals(0,f.published.active); assertTrue(barrier.quiescent)
    }
    @Test fun actualMixedReaderSelectsLegacyPublishedAndCompletedWithoutWrongLegacyBytes() {
        val f=fixture(); seed(f.native,"old",byteArrayOf(1,2)); published(f,"published",byteArrayOf(3,4))
        val fresh="saved/new"; seed(f.native,fresh,byteArrayOf(99)); completed(f,fresh,byteArrayOf(5,6,7))
        assertArrayEquals(byteArrayOf(1,2),read(f,"old")); assertArrayEquals(byteArrayOf(3,4),read(f,"published"))
        assertArrayEquals(byteArrayOf(5,6,7),read(f,fresh)); assertEquals(0,f.published.active); assertEquals(0,f.completed.active)
        assertEquals(PartitionSavePhase.Closed,f.saves.find(fresh)?.phase)
        assertArrayEquals(byteArrayOf(99),requireNotNull(f.native.getCachedSpans(fresh).single().file).readBytes())
    }
    @Test fun unfinishedNewSaveClaimBlocksLegacyFallbackButReadyWinsALaterClaim() {
        val f=fixture(); val key="saved/unfinished"; seed(f.native,key,byteArrayOf(88))
        f.saves.begin(f.catalog.reserve(key))
        assertFalse(f.audio.contains(key)); assertThrows(IOException::class.java) { f.audio.inspect(key) }
        val source=f.audio.source.createDataSource()
        assertThrows(IOException::class.java) { source.open(spec(key)) }; source.close()
        val ready="saved/published"; published(f,ready,byteArrayOf(4,6))
        f.saves.begin(f.catalog.reserveFresh(ready))
        assertTrue(f.audio.contains(ready)); assertArrayEquals(byteArrayOf(4,6),read(f,ready))
        val listed=mutableListOf<String>(); f.audio.forEachKey { listed+=it; true }
        assertEquals(listOf(ready),listed); assertTrue(f.native.isCached(key,0,1))
    }
    @Test fun pendingOrUncertainMigrationPreservesReadableOriginalWithoutPromotingIt() {
        val f=fixture()
        for((i,phase) in listOf(MigrationPhase.Copying,MigrationPhase.Verified,MigrationPhase.Uncertain).withIndex()) {
            val key="legacy/$i"; seed(f.native,key,byteArrayOf(i.toByte()))
            val ticket=f.journal.begin(f.catalog.reserve(key),f.native.uid,null)
            if(phase==MigrationPhase.Verified) f.journal.verified(ticket,f.native.uid xor 1L,MigrationCopyEvidence(1,1))
            if(phase==MigrationPhase.Uncertain) f.journal.uncertain(ticket)
            assertTrue(f.audio.contains(key)); assertArrayEquals(byteArrayOf(i.toByte()),read(f,key))
            assertNull(f.journal.ready(key)); assertEquals(phase,f.journal.find(key)?.phase)
        }
    }
    @Test fun routeChangeDuringLegacyReadRefusesReturnAndRemainsLostUntilCloseEvenIfRestored() {
        val f=fixture(); val key="saved/changed"; seed(f.native,key,byteArrayOf(1,2,3))
        val source=f.audio.source.createDataSource(); var changed=false
        source.addTransferListener(object:TransferListener {
            override fun onTransferInitializing(s:DataSource,spec:DataSpec,network:Boolean)=Unit
            override fun onTransferStart(s:DataSource,spec:DataSpec,network:Boolean)=Unit
            override fun onTransferEnd(s:DataSource,spec:DataSpec,network:Boolean)=Unit
            override fun onBytesTransferred(s:DataSource,spec:DataSpec,network:Boolean,count:Int) {
                if(!changed) { changed=true; f.saves.begin(f.catalog.reserve(key)) }
            }
        })
        source.open(spec(key)); assertThrows(IOException::class.java) { source.read(ByteArray(3),0,3) }
        assertNull(source.uri)
        SQLiteDatabase.openDatabase(File(f.root,"new-save-journal-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DELETE FROM saves")
        }
        assertThrows(IOException::class.java) { source.read(ByteArray(3),0,3) }
        source.close(); assertArrayEquals(byteArrayOf(1,2,3),read(f,key))
    }
    @Test fun inventoryFiltersDuplicatesWithoutOpeningPartitionsOrRetainingGlobalKeySet() {
        val f=fixture()
        val old=(0 until 25).map { "old/%02d".format(it) }; old.forEach { seed(f.native,it,byteArrayOf(1)) }
        val migrated=(0 until 17).map { "migrated/%02d".format(it) }; migrated.forEach { published(f,it,byteArrayOf(2)) }
        val fresh=(0 until 5).map { "saved/%02d".format(it) }; fresh.forEach { completed(f,it,byteArrayOf(3)); seed(f.native,it,byteArrayOf(99)) }
        val listed=mutableListOf<String>(); f.audio.forEachKey { listed+=it; true }
        assertEquals((old+migrated+fresh).toSet(),listed.toSet()); assertEquals(47,listed.size)
        assertEquals(0,f.budget.resident); assertEquals(0,f.published.resident); assertEquals(0,f.completed.resident)
        var visits=0; f.audio.forEachKey { visits++; false }; assertEquals(1,visits)
    }
    @Test fun currentVolumeLossFailsLegacyReadsAndCleanupStillRuns() {
        val f=fixture(); seed(f.native,"old",byteArrayOf(1,2,3)); val source=f.audio.source.createDataSource()
        source.open(spec("old")); f.volume=null
        assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) }
        f.volume="card"; assertThrows(IOException::class.java) { source.read(ByteArray(1),0,1) }
        source.close(); assertArrayEquals(byteArrayOf(1,2,3),read(f,"old"))
    }
}
