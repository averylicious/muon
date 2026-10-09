@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.Closeable
import java.io.IOException

/** Prepared shelf composition, NOT selected by OfflineStore. The app-level owner must supply ONE
 * shared barrier, the process native budget via native, an exact legacy/index/cover claim census,
 * stable volume observation and exclusively owned journals. Journals, provider and legacy are
 * borrowed: only close them after this session actually closes and all app participants drain.
 * No manager/service/UI switch, legacy-import bound, destructive command or recovery authority.
 */
internal class PartitionStorageSession(private val catalog:CachePartitionCatalog,
    private val saves:PartitionSaveJournal,private val migrations:CacheMigrationJournal,
    private val native:PartitionNativeOwner,database:DatabaseProvider,indexName:String,
    legacy:SavedAudio,upstream:DataSource.Factory,unclaimed:(String)->Boolean,
    private val available:()->Boolean,private val barrier:SavedStorageBarrier):Closeable {
    @Volatile private var stopped=false
    @Volatile private var closing=false
    @Volatile private var uncertain=false
    private fun requireOpen() {
        if(stopped || closing || uncertain || !available()) throw IOException("Partition storage session unavailable")
    }
    private val commands=PartitionSaveCommands(catalog,saves,migrations,native,unclaimed,
        { !stopped && !closing && !uncertain && available() })
    private val writers=PartitionCacheLeases(commands::open,capacity=2)
    private val published=PartitionPublishedAudio(native,capacity=2)
    private val completed=PartitionCompletedAudio(native,database,indexName,capacity=2)
    private val mixed=MixedSavedAudio(native,legacy,published,completed)
    val audio:SavedAudio=BarrierSavedAudio(object:SavedAudio {
        override val source=DataSource.Factory { requireOpen(); mixed.source.createDataSource() }
        override fun contains(key:String):Boolean { requireOpen(); return mixed.contains(key) }
        override fun inspect(key:String):SavedAudioState { requireOpen(); return mixed.inspect(key) }
        override fun forEachKey(visit:(String)->Boolean) { requireOpen(); mixed.forEachKey(visit) }
    },barrier)
    val downloaders:DownloaderFactory=PartitionCommandDownloader(commands,writers,upstream,barrier)
    private fun <T> operation(work:()->T):T {
        val permit=barrier.shared()
        try { requireOpen(); permit.check(); return work().also { permit.check() } } finally { permit.close() }
    }
    fun prepare(request:DownloadRequest)=operation { commands.prepare(request) }
    fun forward(command:PartitionSaveCommands.Command,request:DownloadRequest,previous:Download?)=
        operation { commands.forward(command,request,previous) }
    fun delivered(command:PartitionSaveCommands.Command,accepted:Boolean,unconfirmed:Boolean=false)=
        operation { commands.delivered(command,accepted,unconfirmed) }
    fun terminal(download:Download)=operation { commands.terminal(download) }

    /** Always a distinct migration target adapter per attempt. Retire known-idle readers/writers
     * before opening the target, without replacing stopped pools or evicting an active participant.
     * This frees this shelf's idle budget only; other shelves share the same process budget and may
     * still cause truthful admission refusal. Source remains byte-for-byte unchanged after Ready.
     */
    fun migrate(control:CacheMigrationControl,source:Cache,key:String,availableBytes:()->Long,
        checkpoint:()->Unit):MigrationRecord {
        requireOpen()
        val publication=CacheMigrationPublication(catalog,migrations,native.migrationTarget(key))
        val adapter=BarrierCacheMigration(barrier) {
            requireOpen(); writers.trimIdle(); published.trimIdle(); completed.trimIdle()
        }
        return try { adapter.run(control,source,key,publication,availableBytes) {
            requireOpen()
            if(saves.find(key)!=null) throw IOException("Migration cannot replace a new-save claim")
            checkpoint()
        } }
        catch(failure:Throwable) {
            if(publication.ownershipUncertain || failure is MigrationIoUncertain) uncertain=true
            throw failure
        }
    }
    override fun close() {
        val permit=synchronized(this) {
            if(uncertain) throw IOException("Partition session ownership uncertain; borrowed owners retained")
            if(closing) throw IOException("Partition session close already active")
            if(stopped) return
            barrier.exclusive().also { closing=true }
        } // Refuse while even a reader at EOF still owns its child.
        var quarantined=false
        try {
            writers.close(); published.close(); completed.close(); stopped=true
        } catch(failure:Throwable) {
            uncertain=true; permit.quarantine(this); quarantined=true; throw failure
        } finally { closing=false; if(!quarantined) permit.close() }
    }
}
