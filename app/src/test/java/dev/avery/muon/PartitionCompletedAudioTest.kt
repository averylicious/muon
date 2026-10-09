@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
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

/** Real native caches/journals, actual index and read-only sources; disposable bytes only. Prepared
 * components are not app routing, production index pre-projection, total-heap or phone evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionCompletedAudioTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val fixtures=mutableListOf<Fixture>()
    private val managers=mutableListOf<DownloadManager>()
    private val payload=byteArrayOf(2,4,6,8,10)
    private inner class Fixture(private val capacity:Int) {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val migrations=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        val index=DefaultDownloadIndex(database,"completed_${fixtures.size}")
        var volume:String?="card"
        val budget=PartitionNativeBudget(capacity)
        fun owner()=PartitionNativeOwner(catalog,migrations,"card",{volume},budget=budget,saves=saves)
        val owner=owner()
        fun audio(owner:PartitionNativeOwner=this.owner)=PartitionCompletedAudio(owner,
            { key -> index.getDownload(key)?.let { PartitionSaveCompletion.from(it) } },capacity)
        val audio=audio()
        val writers=mutableListOf<PartitionCacheLeases>()
    }
    private fun fixture(capacity:Int=2)=Fixture(capacity).also(fixtures::add)
    private fun request(key:String="saved/one",data:ByteArray=byteArrayOf(1))=
        DownloadRequest.Builder(key,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey(key).setData(data).build()
    private fun row(request:DownloadRequest,length:Long=payload.size.toLong(),state:Int=Download.STATE_COMPLETED)=
        Download(request,state,1,1,length,0,Download.FAILURE_REASON_NONE)
    private fun spec(key:String="saved/one")=DataSpec.Builder().setUri("muon-saved:fixture").setKey(key).build()
    private fun seed(f:Fixture,key:String="saved/one",
        ticket:PartitionSaveTicket=f.saves.begin(f.catalog.reserve(key))):PartitionSaveRecord {
        f.owner.openNewSave(ticket).let { cache ->
            val hole=requireNotNull(cache.startReadWrite(key,0,payload.size.toLong()))
            try {
                val file=cache.startFile(key,0,payload.size.toLong()); file.writeBytes(payload)
                cache.commitFile(file,payload.size.toLong())
            } finally { cache.releaseHoleSpan(hole) }
            cache.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(
                ContentMetadataMutations(),payload.size.toLong()))
            cache.release()
        }
        return requireNotNull(f.saves.find(key))
    }
    private fun bytes(f:Fixture,record:PartitionSaveRecord)=File(f.catalog.directory(record.ticket.allocation),"bytes")
        .walkTopDown().filter { it.isFile && it.name.endsWith(".exo") }.associateWith { it.readBytes() }
    @After fun close() {
        managers.asReversed().forEach { it.release() }
        fixtures.asReversed().forEach { f ->
            f.writers.forEach { it.close() }; f.audio.close(); f.saves.close(); f.migrations.close(); f.catalog.close()
        }
        database.close()
    }
    @Test fun closedWithoutCompletedAndCompletedWithoutClosedNeverPublishOrOpenNative() {
        val f=fixture(); val closed=seed(f)
        assertFalse(f.audio.contains("saved/one"))
        assertThrows(IOException::class.java) { f.audio.inspect("saved/one") }
        val reserved=f.saves.begin(f.catalog.reserve("saved/reserved"))
        f.index.putDownload(row(request(reserved.allocation.key)))
        assertFalse(f.audio.contains(reserved.allocation.key))
        val reader=f.audio.source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec(reserved.allocation.key)) }; reader.close()
        f.audio.forEachKey { error("No completed clean copy exists") }
        assertEquals(0,f.budget.resident); assertEquals(closed,f.saves.find("saved/one"))
        bytes(f,closed).forEach { (file,data) -> assertArrayEquals(payload,data); assertArrayEquals(data,file.readBytes()) }
    }
    @Test fun actualManagerCompletionSurvivesNewOwnerWithoutProcessTokenAndReadsLeaveClosed() {
        val f=fixture(1); val request=request()
        val commands=PartitionSaveCommands(f.catalog,f.saves,f.migrations,f.owner,{true},{f.volume=="card"})
        val pool=PartitionCacheLeases(commands::open,capacity=1).also(f.writers::add)
        val factory=PartitionCommandDownloader(commands,pool,DataSource.Factory { ByteArrayDataSource(payload) })
        val manager=DownloadManager(RuntimeEnvironment.getApplication(),f.index,factory).also(managers::add)
        manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads()
        val command=commands.prepare(request)
        assertTrue(commands.forward(command,request,null)); manager.addDownload(request); commands.delivered(command,true)
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(manager.isInitialized && manager.isIdle && f.index.getDownload(request.id)?.state==Download.STATE_COMPLETED) break
            check(System.nanoTime()<deadline); Thread.sleep(5)
        }
        assertTrue(commands.terminal(requireNotNull(f.index.getDownload(request.id))))
        assertEquals(0,commands.active); assertEquals(0,f.budget.resident)
        val closed=requireNotNull(f.saves.find(request.id)); val original=bytes(f,closed)
        f.audio(f.owner()).use { restarted ->
            assertTrue(restarted.contains(request.id)); assertEquals(SavedCoverage.Full,restarted.inspect(request.id).coverage)
            val reader=restarted.source.createDataSource()
            assertEquals(5L,reader.open(spec())); assertEquals(closed,f.saves.find(request.id))
            val got=ByteArray(5); assertEquals(5,reader.read(got,0,5)); assertArrayEquals(payload,got)
            assertEquals(-1,reader.read(ByteArray(1),0,1)); assertEquals(1,restarted.active)
            reader.close(); assertEquals(closed,f.saves.find(request.id))
        }
        assertEquals(0,f.budget.resident); assertNull(f.migrations.ready(request.id))
        PartitionSaveJournal(f.root).use { assertEquals(closed,it.find(request.id)) }
        assertEquals(request,f.index.getDownload(request.id)?.request)
        original.forEach { (file,data) -> assertArrayEquals(data,file.readBytes()) }
    }
    @Test fun pagedEnumerationSkipsIncompleteWithoutOpeningCachesAndInspectionStaysBounded() {
        val f=fixture(2); val expected=(0 until 19).map { "saved/%02d".format(it) }
        expected.forEach { key -> seed(f,key); f.index.putDownload(row(request(key))) }
        seed(f,"saved/no-index"); seed(f,"saved/failed")
        f.index.putDownload(row(request("saved/failed"),state=Download.STATE_FAILED))
        val listed=mutableListOf<String>(); f.audio.forEachKey { listed+=it; true }
        assertEquals(expected,listed); assertEquals(0,f.budget.resident)
        for(key in expected) {
            assertEquals(SavedCoverage.Full,f.audio.inspect(key).coverage)
            assertTrue(f.audio.resident<=2); assertTrue(f.budget.resident<=2); assertEquals(0,f.audio.active)
            assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase)
        }
        val one=mutableListOf<String>(); f.audio.forEachKey { one+=it; false }; assertEquals(listOf(expected.first()),one)
        f.audio.close(); assertEquals(0,f.budget.resident)
    }
    @Test fun changedLengthAliasAndActiveWriterRefuseWithoutChangingRowsOrBytes() {
        val f=fixture(1); val closed=seed(f); val original=bytes(f,closed); val request=request()
        f.index.putDownload(row(request,length=6))
        assertThrows(IOException::class.java) { f.audio.inspect(request.id) }
        assertEquals(0,f.budget.resident); assertEquals(closed,f.saves.find(request.id))
        val alias=DownloadRequest.Builder(request.id,request.uri).setCustomCacheKey("saved/other").build()
        f.index.putDownload(row(alias))
        assertThrows(IOException::class.java) { f.audio.contains(request.id) }
        f.index.putDownload(row(request))
        val writer=f.owner.openSaved(request.id)
        try { assertFalse(f.audio.contains(request.id)); assertThrows(IOException::class.java) { f.audio.inspect(request.id) } }
        finally { writer.release() }
        assertEquals(SavedCoverage.Full,f.audio.inspect(request.id).coverage)
        assertEquals(closed,f.saves.find(request.id))
        original.forEach { (file,data) -> assertArrayEquals(data,file.readBytes()) }
    }
    @Test fun indexChangeDuringReadRefusesBytesAndIsStickyUntilActualClose() {
        val f=fixture(1); seed(f); val request=request(); val original=row(request); f.index.putDownload(original)
        val reader=f.audio.source.createDataSource()
        reader.addTransferListener(object:TransferListener {
            override fun onTransferInitializing(source:DataSource,spec:DataSpec,network:Boolean)=Unit
            override fun onTransferStart(source:DataSource,spec:DataSpec,network:Boolean)=Unit
            override fun onTransferEnd(source:DataSource,spec:DataSpec,network:Boolean)=Unit
            override fun onBytesTransferred(source:DataSource,spec:DataSpec,network:Boolean,count:Int) {
                f.index.putDownload(row(request(data=byteArrayOf(9))))
            }
        })
        reader.open(spec())
        assertThrows(IOException::class.java) { reader.read(ByteArray(5),0,5) }
        assertEquals(1,f.audio.active); assertNull(reader.uri)
        f.index.putDownload(original)
        assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        reader.close(); assertEquals(0,f.audio.active)
        assertEquals(SavedCoverage.Full,f.audio.inspect(request.id).coverage)
    }
    @Test fun changedAllocationCannotReuseCachedOldNativeInstanceOrCreateAReadFallback() {
        val f=fixture(1); val closed=seed(f); f.index.putDownload(row(request())); val original=bytes(f,closed)
        assertEquals(SavedCoverage.Full,f.audio.inspect("saved/one").coverage)
        f.catalog.reserveFresh("saved/one")
        val reader=f.audio.source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec()) }; reader.close()
        assertEquals(0,f.audio.active); assertThrows(IOException::class.java) { f.audio.contains("saved/one") }
        f.audio.close(); assertEquals(0,f.budget.resident)
        assertEquals(closed,f.saves.find("saved/one")); original.forEach { (file,data) -> assertArrayEquals(data,file.readBytes()) }
    }
    @Test fun replacedCleanRouteCannotReadTheOldUidStillResidentUnderTheSameKey() {
        val f=fixture(2); val old=seed(f); val key=old.ticket.allocation.key; f.index.putDownload(row(request(key)))
        assertEquals(SavedCoverage.Full,f.audio.inspect(key).coverage); assertEquals(1,f.audio.resident)
        // Controlled ownership replacement while the old read-only instance is idle. Production
        // coordinator must drain/exclude it; the reader also independently refuses the reused UID.
        f.saves.uncertain(old.ticket)
        val ticket=f.saves.begin(f.catalog.reserveFresh(key),expectedToken=old.ticket.token)
        val replacement=seed(f,key,ticket); assertNotEquals(old.uid,replacement.uid)
        val reader=f.audio.source.createDataSource()
        assertThrows(IOException::class.java) { reader.open(spec(key)) }; reader.close()
        assertEquals(0,f.audio.active); assertThrows(IOException::class.java) { f.audio.inspect(key) }
        f.audio.close(); assertEquals(0,f.budget.resident)
        f.audio(f.owner()).use { fresh -> assertEquals(SavedCoverage.Full,fresh.inspect(key).coverage) }
        assertEquals(replacement,f.saves.find(key)); assertNull(f.migrations.ready(key))
    }
    @Test fun volumeLossAndReturnDoNotResumeFailedOpenReaderOrEraseCompletion() {
        val f=fixture(1); val closed=seed(f); val row=row(request()); f.index.putDownload(row)
        val reader=f.audio.source.createDataSource(); reader.open(spec()); f.volume=null
        assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        assertThrows(IOException::class.java) { f.audio.contains("saved/one") }
        f.volume="card"; assertThrows(IOException::class.java) { reader.read(ByteArray(1),0,1) }
        reader.close(); reader.open(spec()); assertEquals(5,reader.read(ByteArray(5),0,5)); reader.close()
        assertEquals(closed,f.saves.find("saved/one")); assertEquals(row.request,f.index.getDownload("saved/one")?.request)
    }
    @Test fun shutdownStopsAdmissionButDoesNotForceCloseActiveReaderAtEof() {
        val f=fixture(1); seed(f); f.index.putDownload(row(request()))
        val reader=f.audio.source.createDataSource(); reader.open(spec()); f.audio.close()
        assertThrows(IOException::class.java) { f.audio.contains("saved/one") }
        assertEquals(5,reader.read(ByteArray(5),0,5)); assertEquals(-1,reader.read(ByteArray(1),0,1))
        assertEquals(1,f.audio.active); assertEquals(1,f.budget.resident)
        reader.close(); assertEquals(0,f.budget.resident)
    }
}
