@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.Closeable
import java.io.IOException
import java.io.File

internal data class OwnedPlayedRemoval(val outcome:OfflineStore.SavedRemoval,val bytes:Long=0)
internal data class OwnedPlayedClear(val removed:Long,val removedBytes:Long,val remainingBytes:Long?,val complete:Boolean)

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
    private val available:()->Boolean,private val barrier:SavedStorageBarrier,
    commandCapacity:Int=DOWNLOAD_COMMAND_COUNT):Closeable {
    internal fun usesBarrier(candidate:SavedStorageBarrier):Boolean = candidate===barrier
    @Volatile private var stopped=false
    @Volatile private var closing=false
    @Volatile private var uncertain=false
    private fun requireOpen() {
        if(stopped || closing || uncertain || !available()) throw IOException("Partition storage session unavailable")
    }
    private val commands=PartitionSaveCommands(catalog,saves,migrations,native,unclaimed,
        { !stopped && !closing && !uncertain && available() },capacity=commandCapacity)
    private val writers=PartitionCacheLeases(commands::open,capacity=2)
    private val published=PartitionPublishedAudio(native,capacity=2)
    private val completionIndex=PartitionCompletionIndex(database,indexName)
    private val completed=PartitionCompletedAudio(native,completionIndex::find,capacity=2,allowPlayed=true)
    private val mixed=MixedSavedAudio(native,legacy,published,completed)
    private val playedCopies=PartitionPlayedCopy(catalog,saves,migrations,native,barrier,
        { !stopped && !closing && !uncertain && available() },unclaimed)
    val audio:SavedAudio=BarrierSavedAudio(object:SavedAudio {
        override val source=DataSource.Factory {
            // Constructing this adapter does no I/O. Refuse a stopped session at open, where
            // the barrier can close a returned child and prove this refusal owns no handles.
            val child=mixed.source.createDataSource()
            object:DataSource by child {
                override fun open(spec:DataSpec):Long { requireOpen(); return child.open(spec) }
            }
        }
        override fun contains(key:String):Boolean { requireOpen(); return mixed.contains(key) }
        override fun inspect(key:String):SavedAudioState { requireOpen(); return mixed.inspect(key) }
        override fun forEachKey(visit:(String)->Boolean) { requireOpen(); mixed.forEachKey(visit) }
    },barrier)
    val downloaders:DownloaderFactory=PartitionCommandDownloader(commands,writers,upstream,barrier)
    private fun <T> operation(work:()->T):T {
        val permit=barrier.shared()
        var quarantined=false
        try { requireOpen(); permit.check(); return work().also { permit.check() } }
        catch(failure:Throwable) {
            if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain) {
                uncertain=true; permit.quarantine(failure); quarantined=true
            }
            throw failure
        } finally { if(!quarantined) permit.close() }
    }
    /** Actual mixed played bytes, streamed without a whole key/state collection. This is an
     * observation, never deletion authority. Unknown/overflow refuses admission, not a zero tally.
     */
    fun playedBytes(checkpoint:()->Unit={}):Long=operation {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played census requires a worker" }
        var used=0L
        audio.forEachKey { key ->
            checkpoint()
            if(key.startsWith(PLAYED_PREFIX)) {
                val bytes=audio.inspect(key).bytes
                if(bytes<0) throw IOException("Invalid played-copy byte count")
                used=Math.addExact(used,bytes)
            }
            true
        }
        checkpoint(); used
    }
    private val playedAdmission=java.util.concurrent.atomic.AtomicBoolean(false)
    /** One aggregate admission through actual copy/source/native closure. Other played writers
     * cannot reuse the same headroom. Shared admission pins routes against migration/deletion;
     * explicit manager saves cannot use played keys. Legacy/protected bytes also consume budget.
     * Decline optional caching when full; never delete originals to make room. LRU eviction is a
     * separate policy. A copy's final block may transiently exceed headroom before known cleanup.
     */
    fun copyPlayed(spec:DataSpec,song:ByteArray,origin:String,upstream:DataSource.Factory,
        writerCreated:(PlayedCopyWriter)->Unit={},checkpoint:()->Unit={},limit:()->Long):PartitionSaveTicket {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played copy requires a worker" }
        if(!playedAdmission.compareAndSet(false,true)) throw IOException("Played-copy admission is busy")
        try { return operation {
            val existing=playedBytes(checkpoint)
            if(limit().coerceAtLeast(0)<=existing) throw PlayedCopyTooLarge()
            // Observational opens may occupy idle native slots; retire only actual known-idle
            // pools before the writer. Active readers are not evicted to manufacture headroom.
            writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            checkpoint()
            playedCopies.copy(spec,song,origin,upstream,writerCreated) {
                (limit().coerceAtLeast(0)-existing).coerceAtLeast(0)
            }
        } } finally { playedAdmission.set(false) }
    }
    /** Explicit deletion of a NEW owned played copy only. Original legacy/Ready migration
     * resources and every manager claim are protected. Exclusive admission proves there is no
     * real reader/writer (including EOF); close idle pools before opening the exact target.
     * Closed remains a durable no-fallback claim even after its own bytes/metadata become empty.
     */
    private var retainedPlayedRemoval:Any?=null
    fun removePlayed(key:String,removable:(String)->Boolean)=removePlayedDetailed(key,removable).outcome
    fun removePlayedDetailed(key:String,removable:(String)->Boolean):OwnedPlayedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played removal requires a worker" }
        if(!key.startsWith(playedKey(NEW_SAVE_PREFIX)) || key.length.toLong()*2>MIGRATION_KEY_BYTES)
            return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
        if(stopped || closing || uncertain) return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Unavailable)
        val permit=try { barrier.exclusive() } catch(_:IOException) { return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Busy) }
        var cache:Cache?=null; var quarantined=false
        try {
            requireOpen(); permit.check()
            val expected=native.savedReadRoute(key)
            if(expected !is PartitionReadRoute.NewSave || expected.record.phase!=PartitionSavePhase.Closed ||
                !removable(key) || completionIndex.holdsName(key)) return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
            if(native.savedReadRoute(key)!=expected) return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
            writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            requireOpen(); permit.check()
            cache=native.openClosedSaveRead(key)
            if(native.savedReadRoute(key)!=expected || cache.uid!=expected.record.uid)
                throw IOException("Played-copy ownership changed before removal")
            val bytes=cache.getCachedBytes(key,0,Long.MAX_VALUE)
            if(bytes<0) throw IOException("Invalid played-copy byte count")
            cache.removeResource(key)
            permit.check(); cache.release(); cache=null
            requireOpen(); permit.check()
            return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Sent,bytes)
        } catch(failure:Throwable) {
            if(cache!=null || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain) {
                // No cleanup/release retry after a possibly partial remove/close. Keep the exact
                // returned owner and actual pools until process teardown, without deleting rows.
                val handles=arrayOf(cache,writers,published,completed,failure)
                retainedPlayedRemoval=handles; uncertain=true
                permit.quarantine(handles); quarantined=true
            }
            return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Failed)
        } finally { if(!quarantined) permit.close() }
    }
    fun prepare(request:DownloadRequest)=operation {
        commands.reconcileCompleted(completionIndex::find)
        commands.prepare(request)
    }
    fun containsId(id:String,completedOnly:Boolean=false)=operation { completionIndex.containsId(id,completedOnly) }
    fun prepared(request:DownloadRequest)=operation { commands.prepared(request) }
    fun abandonPrepared(request:DownloadRequest):Boolean=operation {
        val command=commands.prepared(request) ?: return@operation false
        commands.abandon(command,request)
    }
    fun reconcileCompleted():Int=operation { commands.reconcileCompleted(completionIndex::find) }
    fun abandon(command:PartitionSaveCommands.Command,request:DownloadRequest)=operation { commands.abandon(command,request) }
    fun forward(command:PartitionSaveCommands.Command,request:DownloadRequest,previous:Download?)=
        operation { commands.forward(command,request,previous) }
    /** Hold actual shared admission across the service/manager call and its acknowledgement.
     * A false return is known refusal before mutation; a throwing call is unconfirmed, even if
     * manager delivery happened just before the exception. Caller must not report false after Add.
     */
    fun deliver(command:PartitionSaveCommands.Command,request:DownloadRequest,previous:Download?,
        start:()->Boolean):Boolean=operation {
        if(!commands.forward(command,request,previous)) return@operation false
        var returned=false; var accepted=false
        try { accepted=start(); returned=true; accepted }
        finally { commands.delivered(command,accepted,unconfirmed=!returned) }
    }
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
            if(publication.ownershipUncertain || failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain) uncertain=true
            throw failure
        }
    }
    /** Read-only transition path: no legacy SimpleCache is constructed before projection. The
     * caller supplies its correct readonly DB/UID/root and must keep that DB alive until return. */
    fun migrateLegacy(control:CacheMigrationControl,directory:File,uid:Long,key:String,index:SQLiteDatabase,
        availableBytes:()->Long,checkpoint:()->Unit):MigrationRecord {
        requireOpen()
        val publication=CacheMigrationPublication(catalog,migrations,native.migrationTarget(key))
        val adapter=BarrierCacheMigration(barrier) {
            requireOpen(); writers.trimIdle(); published.trimIdle(); completed.trimIdle()
        }
        return try {
            adapter.runProjected(control,key,publication,availableBytes,{
                requireOpen()
                if(saves.find(key)!=null) throw IOException("Migration cannot replace a new-save claim")
                checkpoint()
            }) { checked -> LegacyResourceProjection.read(directory,uid,key,index,checked) }
        } catch(failure:Throwable) {
            if(publication.ownershipUncertain || failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain) uncertain=true
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
