@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
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
class PartitionSaveCommandsTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val fixtures=mutableListOf<Fixture>()
    private val managers=mutableListOf<DownloadManager>()
    private val payload=byteArrayOf(2,4,6,8,10)
    private inner class Fixture(private val capacity:Int=DOWNLOAD_COMMAND_COUNT) {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root)
        val migration=CacheMigrationJournal(root); val saves=PartitionSaveJournal(root,create=true)
        var available=true; var free=true
        val budget=PartitionNativeBudget(2)
        val owner=PartitionNativeOwner(catalog,migration,"phone",{if(available) "phone" else null},budget=budget,saves=saves)
        fun commands(cap:Int=capacity)=PartitionSaveCommands(catalog,saves,migration,owner,{free},{available},cap)
        val commands=commands()
        val pool=PartitionCacheLeases(commands::open,capacity=2)
        var upstreams=0
        val factory=PartitionCommandDownloader(commands,pool,DataSource.Factory { upstreams++; ByteArrayDataSource(payload) })
    }
    private fun fixture(cap:Int=DOWNLOAD_COMMAND_COUNT)=Fixture(cap).also(fixtures::add)
    private fun request(id:String="saved/one",data:ByteArray=byteArrayOf(1))=
        DownloadRequest.Builder(id,Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
            .setCustomCacheKey(id).setData(data).build()
    private fun changed(original:DownloadRequest,data:ByteArray)=DownloadRequest.Builder(original.id,original.uri)
        .setCustomCacheKey(original.customCacheKey).setData(data).build()
    private fun manager(f:Fixture):Pair<DownloadManager,DefaultDownloadIndex> {
        val index=DefaultDownloadIndex(database,"commands")
        val manager=DownloadManager(RuntimeEnvironment.getApplication(),index,f.factory).also(managers::add)
        manager.setRequirements(Requirements(0)); manager.minRetryCount=0; manager.resumeDownloads()
        return manager to index
    }
    private fun await(manager:DownloadManager,ready:()->Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(true) {
            shadowOf(Looper.getMainLooper()).idle()
            if(manager.isInitialized && manager.isIdle && ready()) return
            check(System.nanoTime()<end) { "Manager did not reach expected state" }; Thread.sleep(5)
        }
    }
    @After fun close() {
        managers.asReversed().forEach { it.release() }
        fixtures.asReversed().forEach { it.pool.close(); it.saves.close(); it.migration.close(); it.catalog.close() }
        database.close()
    }
    @Test fun capacityUnsupportedAndClaimedRequestsRefuseBeforeAnyNativeOrExtraReservation() {
        val f=fixture(1); f.commands.prepare(request())
        assertThrows(PartitionCacheBusy::class.java) { f.commands.prepare(request("saved/two")) }
        assertNull(f.catalog.find("saved/two")); assertNull(f.saves.find("saved/two"))
        assertEquals(1L,f.catalog.count()); assertEquals(0,f.budget.resident)
        val g=fixture(); g.free=false
        assertThrows(IOException::class.java) { g.commands.prepare(request()) }
        g.free=true
        val range=DownloadRequest.Builder("saved/range",request().uri).setCustomCacheKey("saved/range").setByteRange(0,2).build()
        val alias=DownloadRequest.Builder("alias",request().uri).setCustomCacheKey("saved/alias").build()
        assertThrows(IOException::class.java) { g.commands.prepare(range) }
        assertThrows(IOException::class.java) { g.commands.prepare(alias) }
        assertThrows(IOException::class.java) { g.commands.prepare(request(data=ByteArray(MOVE_COMMAND_BYTES.toInt()+1))) }
        assertEquals(0L,g.catalog.count()); assertEquals(0,g.budget.resident)
    }
    @Test fun fullRequestIdentityAndSingleUseForwardingAreRequiredBeforeManagerAdmission() {
        val f=fixture(); val request=request(); val command=f.commands.prepare(request)
        assertFalse(f.commands.forward(command,changed(request,byteArrayOf(9)),null))
        assertFalse(f.commands.forward(command.copy(token="wrong"),request,null))
        f.free=false; assertFalse(f.commands.forward(command,request,null)); f.free=true
        assertTrue(f.commands.forward(command,request,null))
        assertFalse(f.commands.forward(command,request,null))
        f.commands.delivered(command,true)
        f.commands.download(request)
        request.data[0]=8
        assertThrows(IOException::class.java) { f.commands.download(request) }
        assertEquals(0,f.pool.resident); assertEquals(1,f.commands.active)
    }
    @Test fun actualManagerCompletesOnlyExactForwardedRequestAfterDurableCloseAndRetainsItsRow() {
        val f=fixture(); val (manager,index)=manager(f); val request=request()
        val command=f.commands.prepare(request); var complete=0
        val cover=File(f.root,"retained-cover").apply { writeBytes(byteArrayOf(9,7)) }
        manager.addListener(object:DownloadManager.Listener {
            override fun onDownloadChanged(manager:DownloadManager,download:Download,finalException:Exception?) {
                if(download.state==Download.STATE_COMPLETED) {
                    assertEquals(PartitionSavePhase.Closed,f.saves.find(request.id)?.phase)
                    assertEquals(0,f.pool.resident); assertTrue(f.commands.terminal(download)); complete++
                }
            }
        })
        assertTrue(f.commands.forward(command,request,index.getDownload(request.id)))
        manager.addDownload(request); f.commands.delivered(command,true)
        await(manager) { index.getDownload(request.id)?.state==Download.STATE_COMPLETED }
        assertEquals(1,complete); assertEquals(0,f.commands.active)
        assertEquals(request,index.getDownload(request.id)?.request)
        val audio=File(f.catalog.directory(command.ticket.allocation),"bytes").walkTopDown().single { it.name.endsWith(".exo") }
        assertArrayEquals(payload,audio.readBytes()); assertArrayEquals(byteArrayOf(9,7),cover.readBytes())
        assertNull(f.migration.ready(request.id)); assertEquals(0,f.budget.resident)
        assertFalse(f.commands.forward(command,request,null))
    }
    @Test fun bypassedUnforwardedRequestFailsOnActualTaskAndManagerStillAcceptsALaterValidSave() {
        val f=fixture(); val (manager,index)=manager(f); val bad=request(); f.commands.prepare(bad)
        // Deliberately bypass future pre-manager gate to prove factory validation cannot kill its handler.
        manager.addDownload(bad)
        await(manager) { index.getDownload(bad.id)?.state==Download.STATE_FAILED }
        assertEquals(bad,index.getDownload(bad.id)?.request); assertEquals(0,f.upstreams)
        assertEquals(PartitionSavePhase.Reserved,f.saves.find(bad.id)?.phase)
        val good=request("saved/good"); val command=f.commands.prepare(good)
        assertTrue(f.commands.forward(command,good,index.getDownload(good.id)))
        manager.addDownload(good); f.commands.delivered(command,true)
        await(manager) { index.getDownload(good.id)?.state==Download.STATE_COMPLETED }
        assertTrue(f.commands.terminal(requireNotNull(index.getDownload(good.id))))
        assertEquals(Download.STATE_FAILED,index.getDownload(bad.id)?.state)
        assertEquals(1,f.upstreams)
    }
    @Test fun processRestartAndChangedAllocationNeverAdoptAnOldCommandOrDeleteItsOriginal() {
        val f=fixture(); val request=request(); val command=f.commands.prepare(request)
        val old=f.catalog.directory(command.ticket.allocation).apply { mkdirs() }
        val original=File(old,"preserve").apply { writeBytes(payload) }
        val restarted=f.commands()
        assertFalse(restarted.forward(command,request,null))
        assertThrows(IOException::class.java) { restarted.prepare(request) }
        assertThrows(IOException::class.java) { restarted.open(request.id) }
        f.catalog.reserveFresh(request.id)
        assertFalse(f.commands.forward(command,request,null))
        assertArrayEquals(payload,original.readBytes()); assertEquals(0,f.budget.resident)
    }
    @Test fun serviceExceptionKeepsUnconfirmedClaimAndVolumeLossRefusesWorkWithoutLosingBytes() {
        val f=fixture(); val request=request(); val command=f.commands.prepare(request)
        assertTrue(f.commands.forward(command,request,null)); f.commands.delivered(command,false,unconfirmed=true)
        assertEquals(1,f.commands.active); f.commands.download(request)
        f.available=false
        assertThrows(IOException::class.java) { f.commands.download(request) }
        assertThrows(IOException::class.java) { f.commands.open(request.id) }
        f.available=true
        assertThrows(IOException::class.java) { f.commands.prepare(request) }
        f.commands.download(request)
        assertEquals(PartitionSavePhase.Reserved,f.saves.find(request.id)?.phase)
    }
    @Test fun existingManagerRecordCannotBeMergedOrReplacedByANewSaveCommand() {
        val f=fixture(); val (manager,index)=manager(f); val request=request()
        val command=f.commands.prepare(request)
        // A prior retained row is a separate ownership claim, even when its ID/request matches.
        val previous=Download(request,Download.STATE_STOPPED,1,1,5,1,0)
        index.putDownload(previous)
        assertFalse(f.commands.forward(command,request,index.getDownload(request.id)))
        assertEquals(previous.request,index.getDownload(request.id)?.request)
        assertEquals(Download.STATE_STOPPED,index.getDownload(request.id)?.state)
        assertEquals(0,f.budget.resident); assertEquals(0,f.upstreams)
    }
    @Test fun cancelBeforeTaskWorkNeverOpensNativeStorageOrGrantsCompletion() {
        val f=fixture(); val request=request(); val command=f.commands.prepare(request)
        assertTrue(f.commands.forward(command,request,null))
        val task=f.factory.createDownloader(request); task.cancel()
        assertThrows(CancellationException::class.java) { task.download(null) }
        val fake=Download(request,Download.STATE_COMPLETED,1,1,5,0,0)
        assertFalse(f.commands.terminal(fake))
        assertEquals(PartitionSavePhase.Reserved,f.saves.find(request.id)?.phase)
        assertEquals(0,f.budget.resident); assertEquals(0,f.upstreams)
    }
}
