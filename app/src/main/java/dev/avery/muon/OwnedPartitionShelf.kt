@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

internal enum class PartitionShelfStart { Fresh, Resume }
internal enum class PartitionOriginalMoveStage { Waiting, Copied, Published, Pending, Complete }
/** Fixed result only; durable recovery rereads the bounded journals, never a retained request/list. */
internal data class PartitionOriginalMoveResult(val stage:PartitionOriginalMoveStage,val outcome:OfflineStore.SavedRemoval,val bytes:Long=0)

/** Real shelf resources assembled WITHOUT first opening the legacy SimpleCache. Register this
 * process owner in OfflineStore's StorageStartup before prepare(); production selection/opt-in is
 * still required. Fresh is explicit and refuses existing roots; Resume refuses missing journals.
 * No live backend switch, source cleanup, automatic repair or unsafe shutdown/reset API.
 */
internal class OwnedPartitionShelf(private val context:Context,private val directory:File,
    private val legacyDirectory:File,private val legacyDatabase:File,private val database:DatabaseProvider,
    private val indexName:String,private val volume:String,private val currentVolume:()->String?,
    private val start:PartitionShelfStart,private val barrier:SavedStorageBarrier,
    /** Complete app-wide current key/index/played/cover claim census, not merely this shelf. */
    private val unclaimed:(String)->Boolean,private val upstream:DataSource.Factory,
    private val budget:PartitionNativeBudget=PartitionNativeBudget.process) {
    internal val storageBarrier:SavedStorageBarrier get()=barrier
    init { require(volume.isNotEmpty()) }
    private val startup=StorageStartup<Resources>()
    private enum class Phase { New,Preparing,Ready,Uncertain }
    @Volatile private var phase=Phase.New
    private var rootIdentity:Any?=null
    private val journalNames=arrayOf("partition-locators-v1.db","migration-journal-v1.db","new-save-journal-v1.db","legacy-retirements-v1.db")
    private val identities=arrayOfNulls<Any>(journalNames.size)
    private class Resources(val legacy:LegacyTransitionOwner,val retirements:LegacyRetirementJournal,val catalog:CachePartitionCatalog,
        val migrations:CacheMigrationJournal,val saves:PartitionSaveJournal,val session:PartitionStorageSession,
        val manager:PartitionShelfManager,val workers:java.util.concurrent.ExecutorService,val work:PartitionMigrationWork)
    private fun exactDirectory(file:File) {
        if(!file.isDirectory || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Partition shelf directory identity differs")
    }
    private fun key(file:File):Any=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS).fileKey() ?: throw IOException("Partition shelf file identity cannot be observed")
    private fun exactJournal(file:File) {
        if(!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Partition shelf journal is missing or changed")
    }
    private fun located() {
        if(currentVolume()!=volume) throw IOException("Partition shelf volume unavailable or changed")
        exactDirectory(directory)
        if(key(directory)!=rootIdentity) throw IOException("Partition shelf root replaced")
        for(i in journalNames.indices) {
            val file=File(directory,journalNames[i]); exactJournal(file)
            if(key(file)!=identities[i]) throw IOException("Partition shelf journal replaced")
        }
    }
    private fun available():Boolean = phase==Phase.Ready && runCatching { located(); true }.getOrDefault(false)
    private fun preflight() {
        if(currentVolume()!=volume || directory.absoluteFile!=directory.canonicalFile ||
            directory.toPath().startsWith(legacyDirectory.toPath()) || legacyDirectory.toPath().startsWith(directory.toPath()))
            throw IOException("Partition shelf cannot alias or replace legacy storage")
        exactDirectory(requireNotNull(directory.parentFile))
        when(start) {
            PartitionShelfStart.Fresh -> if(directory.exists()) throw IOException("Fresh partition shelf root already exists")
            PartitionShelfStart.Resume -> {
                exactDirectory(directory)
                rootIdentity=key(directory)
                for(i in journalNames.indices) {
                    val file=File(directory,journalNames[i]); exactJournal(file); identities[i]=key(file)
                }
                located()
            }
        }
    }
    /** Bounded filesystem/schema work; run off main before the UI/service exposes this backend.
     * A failed attempt is sticky and retains resources. Never construct another root over it.
     */
    @Synchronized fun prepare() {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Partition shelf preparation requires a worker"}
        if(phase==Phase.Ready) { located(); return }
        if(phase!=Phase.New) throw IOException("Partition shelf startup is active or uncertain")
        phase=Phase.Preparing
        try { startup.open { owner ->
            preflight()
            val legacy=owner.own { LegacyTransitionOwner(legacyDirectory,legacyDatabase,barrier,{currentVolume()==volume}) }
            val audio=legacy.open()
            if(start==PartitionShelfStart.Fresh) Files.createDirectory(directory.toPath())
            exactDirectory(directory)
            if(start==PartitionShelfStart.Fresh) rootIdentity=key(directory) else located()
            val catalog=owner.own { CachePartitionCatalog(directory,create=start==PartitionShelfStart.Fresh) }
            if(start==PartitionShelfStart.Resume) located()
            val migrations=owner.own { CacheMigrationJournal(directory,create=start==PartitionShelfStart.Fresh) }
            if(start==PartitionShelfStart.Resume) located()
            val saves=owner.own { PartitionSaveJournal(directory,create=start==PartitionShelfStart.Fresh) }
            val retirements=owner.own {LegacyRetirementJournal(directory,start==PartitionShelfStart.Fresh)}
            retirements.open()
            if(start==PartitionShelfStart.Fresh) for(i in journalNames.indices) {
                val file=File(directory,journalNames[i]); exactJournal(file); identities[i]=key(file)
            }
            located()
            val native=owner.own { PartitionNativeOwner(catalog,migrations,volume,
                { if(runCatching { located(); true }.getOrDefault(false)) volume else null },budget=budget,saves=saves) }
            val index=PartitionCompletionIndex(database,indexName)
            val session=owner.own { PartitionStorageSession(catalog,saves,migrations,native,database,indexName,
                audio,upstream,{ name -> !retirements.holdsName(name) && !audio.contains(name) && !index.holdsName(name) && unclaimed(name) },::available,barrier,retirements=retirements) }
            val manager=owner.own { PartitionShelfManager(context.applicationContext,database,indexName,session,barrier) }
            val workers=owner.own { java.util.concurrent.Executors.newSingleThreadExecutor() }
            val work=owner.own { PartitionMigrationWork(workers,{ control,name ->
                legacy.migrate(session,control,name,{ directory.usableSpace }) { located() }
            },::available,knownRejectionBeforeSubmission=true) }
            Resources(legacy,retirements,catalog,migrations,saves,session,manager,workers,work)
        }; phase=Phase.Ready }
        catch(failure:Throwable) {
            phase=Phase.Uncertain; barrier.invalidate(); throw failure
        }
    }
    private fun resources():Resources {
        if(!available()) throw IOException("Partition shelf is not prepared or its identity changed")
        return startup.open { throw IOException("Partition shelf resources missing") }
    }
    val isAvailable:Boolean get()=available()
    val audio:SavedAudio get()=resources().session.audio
    fun initializeManager():DownloadManager=resources().manager.initialize()
    fun managerForService():DownloadManager=resources().manager.managerForService()
    fun claimsName(name:String):Boolean=resources().session.claimsName(name)
    fun containsId(id:String,completedOnly:Boolean=false)=resources().session.containsId(id,completedOnly)
    fun copyPlayed(spec:androidx.media3.datasource.DataSpec,song:ByteArray,origin:String,upstream:DataSource.Factory,
        writerCreated:(PlayedCopyWriter)->Unit={},checkpoint:()->Unit={},limit:()->Long)=
        resources().session.copyPlayed(spec,song,origin,upstream,writerCreated,checkpoint,limit)
    internal fun copyCompletedOriginalTo(destination:OwnedPartitionShelf,key:String,newKey:String,
        explicitConsent:Boolean,checkpoint:()->Unit={}):PartitionOriginalCopy {
        if(destination===this || destination.storageBarrier!==barrier)
            throw IOException("Local Move preparation needs another shelf on the actual gate")
        val source=resources(); val target=destination.resources()
        return source.legacy.copyCompletedOriginalTo(source.session,target.session,key,newKey,explicitConsent) {
            located(); destination.located(); checkpoint()
        }
    }
    internal fun publishCopiedOriginalTo(destination:OwnedPartitionShelf,copy:PartitionOriginalCopy,
        explicitConsent:Boolean,unclaimedElsewhere:(String)->Boolean,timestamp:Long=System.currentTimeMillis(),checkpoint:()->Unit={}):PartitionSaveCompletion {
        if(destination===this || destination.storageBarrier!==barrier)
            throw IOException("Local publication requires another shelf on the actual gate")
        val source=resources(); val target=destination.resources()
        return source.legacy.publishCopiedOriginalTo(source.session,target.session,copy,explicitConsent,
            unclaimedElsewhere,{located(); destination.located(); checkpoint()},timestamp,{name,permit -> claimsMoveName(name,permit)},
            {located(); destination.located()})
    }
    internal fun copyCompletedSavedTo(destination:OwnedPartitionShelf,key:String,newKey:String,
        explicitConsent:Boolean,checkpoint:()->Unit={}):PartitionSavedCopy {
        if(destination===this || destination.storageBarrier!==barrier)
            throw IOException("Saved copy requires another shelf on the actual gate")
        val source=resources(); val target=destination.resources()
        return source.session.copyCompletedSavedTo(target.session,key,newKey,explicitConsent) {
            located(); destination.located(); checkpoint()
        }
    }
    internal fun publishCopiedSavedTo(destination:OwnedPartitionShelf,copy:PartitionSavedCopy,
        explicitConsent:Boolean,unclaimedElsewhere:(String)->Boolean,timestamp:Long=System.currentTimeMillis(),
        checkpoint:()->Unit={}):PartitionSaveCompletion {
        if(destination===this || destination.storageBarrier!==barrier)
            throw IOException("Saved publication requires another shelf on the actual gate")
        val source=resources(); val target=destination.resources()
        return source.session.publishCopiedSavedTo(target.session,copy,explicitConsent,unclaimedElsewhere,
            {name,permit -> claimsMoveName(name,permit)},checkpoint,{located(); destination.located()},timestamp)
    }
    internal fun beginCopiedOriginalMove(destination:OwnedPartitionShelf,copy:PartitionOriginalCopy,
        completed:PartitionSaveCompletion,explicitConsent:Boolean):LegacyRetirementRecord {
        if(destination===this || destination.storageBarrier!==barrier)
            throw IOException("Move intent requires another actual shelf on the same gate")
        val source=resources(); val target=destination.resources()
        return source.legacy.beginMovedRetirement(source.session,source.retirements,copy,explicitConsent,{permit ->
            destination.located()
            target.session.moveDestinationIdentity(copy.destination,completed,destination.volume,
                destination.key(destination.directory).toString(),permit) {destination.located()}
        },checked={located(); destination.located()})
    }
    /** Own-cover binding is persisted before ANY source cleanup; source bytes/record are rechecked
     * under the same gate. Exact older unbound Pending can upgrade only while all originals remain. */
    internal fun beginCoveredOriginalMove(destination:OwnedPartitionShelf,copy:PartitionOriginalCopy,
        completed:PartitionSaveCompletion,explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,
        recovery:LegacyRetirementRecord?=null,checkpoint:()->Unit={}):LegacyRetirementRecord {
        if(destination===this || destination.storageBarrier!==barrier)
            throw IOException("Covered Move needs another actual shelf on the same gate")
        val source=resources(); val target=destination.resources()
        return source.legacy.beginMovedRetirement(source.session,source.retirements,copy,explicitConsent,{permit ->
            destination.located()
            target.session.moveDestinationIdentity(copy.destination,completed,destination.volume,
                destination.key(destination.directory).toString(),permit) {destination.located()}
        },{permit ->
            artwork.cancelForConfirmedRemoval(copy.source.key,permit)
            artwork.cancelForConfirmedRemoval(copy.destination.allocation.key,permit)
            art.copyEntryConfirmed(copy.source.key,copy.destination.allocation.key,permit)
        },recovery) {located(); destination.located(); checkpoint()}
    }
    internal fun completeCoveredOriginalMove(destination:OwnedPartitionShelf,expected:LegacyRetirementRecord,
        explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,
        unclaimedElsewhere:(String)->Boolean,checkpoint:()->Unit={}):OfflineStore.SavedRemoval {
        if(destination===this || destination.storageBarrier!==barrier) return OfflineStore.SavedRemoval.NotOwned
        val bound=expected.manifest.moveDestination ?: return OfflineStore.SavedRemoval.Waiting
        val cover=expected.manifest.moveCover ?: return OfflineStore.SavedRemoval.Waiting
        val source=resources(); val target=destination.resources()
        return source.legacy.completeMovedOriginal(source.session,source.retirements,expected,explicitConsent,
            unclaimedElsewhere,{permit ->
                located(); destination.located()
                target.session.verifyMoveDestination(bound,destination.volume,
                    destination.key(destination.directory).toString(),permit) {located(); destination.located()}
                artwork.cancelForConfirmedRemoval(expected.manifest.key,permit)
                artwork.cancelForConfirmedRemoval(bound.ticket.allocation.key,permit)
                art.verifyMovedTarget(bound.ticket.allocation.key,cover,permit)
                art.verifyMovedSource(expected.manifest.key,cover,permit)
            },{key,permit ->art.removeMovedSourceConfirmed(key,bound.ticket.allocation.key,cover,permit)},
            {located(); destination.located(); checkpoint()},{name,permit -> destination.claimsMoveName(name,permit)})
    }
    /** One synchronous, explicitly admitted original Move, for the existing serial mover. No new
     * executor, backlog, request list or automatic restart replay. Stages can cancel cooperatively;
     * a target already published stays readable, and exact Pending recovery is a separate consent.
     * Storage checks carry checkpoint through real source read/digest/disposition loops. */
    internal fun moveCompletedOriginalTo(destination:OwnedPartitionShelf,key:String,newKey:String,
        explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,unclaimedElsewhere:(String)->Boolean,
        checkpoint:()->Unit={},boundary:(PartitionOriginalMoveStage)->Unit={}):PartitionOriginalMoveResult {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original Move requires a worker"}
        if(!explicitConsent) return PartitionOriginalMoveResult(PartitionOriginalMoveStage.Waiting,OfflineStore.SavedRemoval.NotOwned)
        var stage=PartitionOriginalMoveStage.Waiting; var bytes=0L
        try {
            checkpoint()
            val copy=copyCompletedOriginalTo(destination,key,newKey,true,checkpoint)
            bytes=copy.source.length; stage=PartitionOriginalMoveStage.Copied; boundary(stage); checkpoint()
            val completed=publishCopiedOriginalTo(destination,copy,true,unclaimedElsewhere,checkpoint=checkpoint)
            stage=PartitionOriginalMoveStage.Published; boundary(stage); checkpoint()
            val pending=beginCoveredOriginalMove(destination,copy,completed,true,art,artwork,checkpoint=checkpoint)
            stage=PartitionOriginalMoveStage.Pending; boundary(stage); checkpoint()
            val result=completeCoveredOriginalMove(destination,pending,true,art,artwork,unclaimedElsewhere,checkpoint)
            if(result==OfflineStore.SavedRemoval.Sent) stage=PartitionOriginalMoveStage.Complete
            return PartitionOriginalMoveResult(stage,result,bytes)
        } catch(failure:Exception) {
            val outcome=if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain ||
                failure is SavedCoverCloseUncertain || !barrier.allowsShared) OfflineStore.SavedRemoval.Unavailable
                else OfflineStore.SavedRemoval.Failed
            return PartitionOriginalMoveResult(stage,outcome,bytes)
        }
    }
    private fun claimsMoveName(name:String,permit:SavedStorageBarrier.Lease):Boolean {
        val made=resources(); located()
        return made.session.claimsPartitionNameUnderExclusive(name,permit) || made.legacy.claimsOriginalName(name,permit)
    }
    fun playedBytes(checkpoint:()->Unit={}):Long=resources().session.playedBytes(checkpoint)
    fun removePlayed(key:String,removable:(String)->Boolean):OfflineStore.SavedRemoval =
        try { resources().session.removePlayed(key,removable) }
        catch(_:IOException) { OfflineStore.SavedRemoval.Unavailable }
    /** Bounded Clear of eligible new played copies. Every target gets actual exclusive admission;
     * the page lease is closed first, so it cannot authorize deletion or mask an active reader.
     * Transition originals/manager claims stay protected. Remaining bytes are an observed tally,
     * not an empty-library claim on failure. No whole-key set or per-copy result history is kept.
     */
    fun clearPlayed(removable:(String)->Boolean):OwnedPlayedClear {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played Clear requires a worker" }
        var removed=0L; var removedBytes=0L; var successful=true; var after:String?=null
        try {
            scan@ while(true) {
                val page=saveRecoveryPage(after)
                if(page.isEmpty()) break
                for(row in page) {
                    val key=row.ticket.allocation.key
                    if(row.phase!=PartitionSavePhase.Closed || !key.startsWith(playedKey(NEW_SAVE_PREFIX))) continue
                    val result=resources().session.removePlayedDetailed(key,removable)
                    when(result.outcome) {
                        OfflineStore.SavedRemoval.Sent -> if(result.bytes>0) {
                            removed=Math.addExact(removed,1); removedBytes=Math.addExact(removedBytes,result.bytes)
                        }
                        OfflineStore.SavedRemoval.NotOwned -> successful=false
                        else -> { successful=false; break@scan }
                    }
                }
                after=page.last().ticket.allocation.key
            }
        } catch(_:Exception) { successful=false }
        val remaining=try { playedBytes() } catch(_:Exception) { null }
        return OwnedPlayedClear(removed,removedBytes,remaining,successful && remaining==0L)
    }
    fun resizePlayed(limit:Long,removable:(String)->Boolean):OwnedPlayedClear =
        resources().session.resizePlayed(limit,removable) {
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Played resize interrupted")
        }
    fun removeCompleted(key:String,unclaimedElsewhere:(String)->Boolean):OfflineStore.SavedRemoval =
        try { resources().session.removeCompleted(key,unclaimedElsewhere) }
        catch(_:IOException) { OfflineStore.SavedRemoval.Unavailable }
    fun prepareSave(request:DownloadRequest)=resources().manager.prepare(request)
    fun abandonPrepared(request:DownloadRequest)=resources().manager.abandonPrepared(request)
    fun deliverPrepared(request:DownloadRequest,start:(DownloadManager)->Unit)=resources().manager.deliverPrepared(request,start)
    fun abandon(command:PartitionSaveCommands.Command,request:DownloadRequest)=resources().manager.abandon(command,request)
    fun deliver(command:PartitionSaveCommands.Command,request:DownloadRequest,start:(DownloadManager)->Unit)=
        resources().manager.deliver(command,request,start)
    val migrationProgress:MigrationWorkProgress get()=resources().work.progress
    val migrationBusy:Boolean get()=resources().work.busy
    fun startMigration(name:String,timeoutMillis:Long,explicitConsent:Boolean)=
        resources().work.start(name,timeoutMillis,explicitConsent)
    fun cancelMigration()=resources().work.cancel()
    /** Bounded disk pages for opt-in/recovery controls. No native cache is opened and no whole
     * journal, metadata or exception history survives. Call off main; pages are observations only.
     * An interrupted new save remains preserved, never automatically resumed/adopted or deleted.
     */
    /** Internal controller boundary only: durable consent/route suppression is NOT physical Delete.
     * Final app control must finish exact source/index disposition or expose pending recovery.
     */
    internal fun beginOriginalRetirement(name:String,explicitConsent:Boolean):LegacyRetirementRecord {
        val made=resources()
        return made.legacy.beginRetirement(made.session,made.retirements,name,explicitConsent) {located()}
    }
    /** Files-only stage; not user-visible Delete and never marks the record Complete. */
    internal fun retireOriginalFiles(expected:LegacyRetirementRecord,explicitConsent:Boolean):LegacyRetirementFileResult {
        val made=resources()
        return made.legacy.retirePendingFiles(made.session,made.retirements,expected,explicitConsent,{located()})
    }
    internal fun removeOriginalCompleted(key:String,explicitConsent:Boolean,
        unclaimedElsewhere:(String)->Boolean,disposeCover:(String,SavedStorageBarrier.Lease)->Unit,
        recovery:LegacyRetirementRecord?=null):OfflineStore.SavedRemoval = try {
        val made=resources()
        made.legacy.deleteCompletedOriginal(made.session,made.retirements,key,explicitConsent,recovery,
            unclaimedElsewhere,disposeCover,{located()})
    } catch(_:IOException) {OfflineStore.SavedRemoval.Unavailable}
    fun retirementRecoveryPage(after:String?=null):List<LegacyRetirementRecord> = recoveryRead {it.retirements.page(after)}
    fun migrationRecoveryPage(after:String?=null):List<MigrationRecord> = recoveryRead { it.migrations.page(after) }
    fun saveRecoveryPage(after:String?=null):List<PartitionSaveRecord> = recoveryRead { it.saves.page(after) }
    private fun <T> recoveryRead(read:(Resources)->T):T {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Recovery reads require a worker" }
        val permit=barrier.shared()
        try { val made=resources(); permit.check(); return read(made).also { located(); permit.check() } }
        finally { permit.close() }
    }
    /** Explicit fresh replacement of the exact interrupted migration shown to the user. No old
     * target is reopened, overwritten or removed; worker admission/publication still recheck it.
     * Returns submission status only, not completion. Unknown current-process I/O remains blocked.
     */
    fun retryMigration(record:MigrationRecord,timeoutMillis:Long,explicitConsent:Boolean):Boolean {
        if(!explicitConsent || record.phase==MigrationPhase.Ready) return false
        return resources().work.start(record.ticket.allocation.key,timeoutMillis,true,MigrationExpectation.Retry(record))
    }
    fun migrate(control:CacheMigrationControl,name:String,availableBytes:()->Long,checkpoint:()->Unit):MigrationRecord {
        val made=resources()
        return made.legacy.migrate(made.session,control,name,availableBytes) { located(); checkpoint(); located() }
    }
}
