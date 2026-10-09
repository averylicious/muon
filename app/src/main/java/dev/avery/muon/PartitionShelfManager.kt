@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import android.os.Looper
import androidx.media3.database.DatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.Closeable
import java.io.File
import java.io.IOException

/** Prepared real manager/service binding for one owned partition session. OfflineStore must select
 * it before legacy native construction and route EVERY producer through its delivery policy. The
 * session/provider/barrier are borrowed; close them only after this manager and actual I/O drain.
 * No default switch, live manager replacement, automatic legacy restart or removal support.
 */
internal class PartitionShelfManager(private val context:Context,private val database:DatabaseProvider,
    private val indexName:String,private val session:PartitionStorageSession,
    private val barrier:SavedStorageBarrier) : Closeable {
    init { require(session.usesBarrier(barrier)) { "Partition manager must share the session storage gate" } }
    private val startup=StorageStartup<DownloadManager>(1)
    private val completion=PartitionCompletionIndex(database,indexName)
    private val commands=DownloadCommandBudget()
    private enum class Phase { Open,Closing,Closed,Uncertain }
    @Volatile private var phase=Phase.Open
    private var attached=false
    private var manager:DownloadManager?=null
    private fun main() {
        check(Looper.myLooper()==Looper.getMainLooper()) { "Partition manager binding/delivery requires main" }
    }
    private fun live() { if(phase!=Phase.Open) throw IOException("Partition manager is closed or uncertain") }
    private fun obtain():DownloadManager {
        main(); live()
        val permit=barrier.shared(); var quarantined=false
        try { return startup.open { opening ->
            val index=ManagerStartupIndex(RetainedDownloadIndex(DefaultDownloadIndex(database,indexName),
                File(context.cacheDir,"muon-retained-startup-${indexName.ifEmpty { "phone" }}.ids")))
            opening.own { DownloadManager(context.applicationContext,index,session.downloaders) }.also { made ->
                manager=made
                made.maxParallelDownloads=2
                made.addListener(object:DownloadManager.Listener {
                    override fun onDownloadChanged(manager:DownloadManager,download:Download,finalException:Exception?) {
                        if(download.state==Download.STATE_COMPLETED || download.state==Download.STATE_FAILED)
                            runCatching { session.terminal(download) }
                        // Exclusive migration can win before this callback. Only exact persisted
                        // completion reconciliation can later reclaim the bounded scalar receipt.
                    }
                    override fun onIdle(manager:DownloadManager) { runCatching { session.reconcileCompleted() } }
                })
            }
        } } catch(failure:Throwable) {
            phase=Phase.Uncertain; permit.quarantine(this); quarantined=true; throw failure
        } finally { if(!quarantined) permit.close() }
    }
    /** Concrete DownloadService helper keeps this exact manager for the process. Once attached,
     * ordinary close cannot release it and leave the helper pointing at a dead manager.
     */
    fun managerForService():DownloadManager=obtain().also { attached=true }
    fun initialize():DownloadManager=obtain()
    fun prepare(request:DownloadRequest):PartitionSaveCommands.Command { live(); return session.prepare(request) }
    fun abandon(command:PartitionSaveCommands.Command,request:DownloadRequest):Boolean {
        live(); return session.abandon(command,request)
    }
    /** Invoke the real service Add only after this owner admits its exact new request. A callback
     * exception is unconfirmed, not refusal. No previous full Download is materialized: any existing
     * ID in any state refuses before manager.addDownload can merge/hydrate its retained payload.
     */
    fun deliver(command:PartitionSaveCommands.Command,request:DownloadRequest,
        start:(DownloadManager)->Unit):Boolean {
        val actual=obtain()
        if(!actual.isInitialized) return false
        if(completion.holdsId(request.id)) { session.abandon(command,request); return false }
        return session.deliver(command,request,null) {
            if(!commands.admit(actual,request,null)) false
            else { start(actual); true }
        }
    }
    /** Destructive commands MUST be refused before Media3. The prepared downloader's refusal alone
     * cannot keep the index row. Production removal routing remains required before opt-in. */
    fun remove(@Suppress("UNUSED_PARAMETER") id:String):Boolean { main(); live(); return false }

    override fun close() {
        main()
        if(phase==Phase.Closed) return
        live()
        if(attached) throw IOException("Service helper retains this manager for the process; live replacement is unsupported")
        val actual=manager
        if(actual!=null && (!actual.isInitialized || !actual.isIdle))
            throw IOException("Partition manager must finish actual work before close")
        val permit=barrier.exclusive(); var quarantined=false
        phase=Phase.Closing
        try { actual?.release(); phase=Phase.Closed }
        catch(failure:Throwable) {
            phase=Phase.Uncertain; permit.quarantine(this); quarantined=true; throw failure
        } finally { if(!quarantined) permit.close() }
    }
}
