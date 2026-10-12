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

private class PartitionDeleteAdmissionBusy(cause:IOException):IOException("Saved Delete admission is busy",cause)

internal enum class PartitionShelfStart { Fresh, Resume }
internal enum class PartitionOriginalMoveStage { Waiting, Copied, Published, Pending, Complete }
/** Fixed result only; durable recovery rereads the bounded journals, never a retained request/list. */
internal data class PartitionOriginalMoveResult(val stage:PartitionOriginalMoveStage,val outcome:OfflineStore.SavedRemoval,val bytes:Long=0)

/** Fixed recovery result; no saved request, manifest history or bound audio retained by UI. */
internal data class PartitionMoveRecoveryResult(val outcome:OfflineStore.SavedRemoval,
    val destination:String?=null,val bytes:Long=0)

/** Fixed UI payload; never retains an audio proof, download request or journal binding. */
internal enum class PartitionRecoveryAction { Move, Delete, UnsupportedDelete }
internal data class PartitionStorageRecoverySummary(val key:String,val token:String,val bytes:Long,val action:PartitionRecoveryAction)
internal data class PartitionStorageRecoveryPage(val entries:List<PartitionStorageRecoverySummary>,val after:String?,val hasMore:Boolean)

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
    private val journalNames=arrayOf("partition-locators-v1.db","migration-journal-v1.db","new-save-journal-v1.db","legacy-retirements-v1.db","partition-moves-v1.db","partition-deletes-v1.db")
    private val identities=arrayOfNulls<Any>(journalNames.size)
    private class Resources(val legacy:LegacyTransitionOwner,val retirements:LegacyRetirementJournal,val moves:PartitionSavedMoveJournal,val deletes:PartitionSavedDeleteJournal,val catalog:CachePartitionCatalog,
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
            val moves=owner.own {PartitionSavedMoveJournal(directory,start==PartitionShelfStart.Fresh)}
            moves.open()
            val deletes=owner.own {PartitionSavedDeleteJournal(directory,start==PartitionShelfStart.Fresh)}
            deletes.open()
            if(start==PartitionShelfStart.Fresh) for(i in journalNames.indices) {
                val file=File(directory,journalNames[i]); exactJournal(file); identities[i]=key(file)
            }
            located()
            val native=owner.own { PartitionNativeOwner(catalog,migrations,volume,
                { if(runCatching { located(); true }.getOrDefault(false)) volume else null },budget=budget,saves=saves) }
            val index=PartitionCompletionIndex(database,indexName)
            val session=owner.own { PartitionStorageSession(catalog,saves,migrations,native,database,indexName,
                audio,upstream,{ name -> !retirements.holdsName(name) && !moves.holdsName(name) && !deletes.holdsName(name) && !audio.contains(name) && !index.holdsName(name) && unclaimed(name) },::available,barrier,retirements=retirements,moves=moves,deletes=deletes) }
            val manager=owner.own { PartitionShelfManager(context.applicationContext,database,indexName,session,barrier) }
            val workers=owner.own { java.util.concurrent.Executors.newSingleThreadExecutor() }
            val work=owner.own { PartitionMigrationWork(workers,{ control,name ->
                legacy.migrate(session,control,name,{ directory.usableSpace }) { located() }
            },::available,knownRejectionBeforeSubmission=true) }
            Resources(legacy,retirements,moves,deletes,catalog,migrations,saves,session,manager,workers,work)
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
    /** Durable explicit Delete preparation only: actual files/row/cover stay unchanged.
     * Register the real journal first, close native pools, and bind the exact source under one gate.
     * Pending suppresses reads/competing commands across restart; final removal is separate. */
    internal fun beginSavedDelete(key:String,explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,
        unclaimedElsewhere:(String)->Boolean,checkpoint:()->Unit={},other:OwnedPartitionShelf?=null):PartitionSavedDeleteRecord {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved Delete intent requires a worker"}
        if(!explicitConsent || other===this || other!=null && other.storageBarrier!==barrier) throw IOException("Saved Delete requires consent and exact same-gate owners")
        val made=resources();val permit=try {barrier.exclusive()} catch(failure:IOException) {throw PartitionDeleteAdmissionBusy(failure)}
        var quarantined=false
        val treeOwnership=MigrationIoOwnership()
        fun live() {permit.check();located();checkpoint();permit.check();located()}
        fun unclaimed(name:String)=other?.claimsMoveName(name,permit)!=true && unclaimedElsewhere(name)
        try {
            live()
            if(made.legacy.claimsOriginalName(key,permit)) throw IOException("Delete source aliases legacy audio")
            val before=made.session.savedDeleteSource(key,permit,::unclaimed)
            artwork.cancelForConfirmedRemoval(key,permit)
            val cover=art.savedDeleteCover(key,permit)
            val saved=before.first;val completed=before.second
            val tree=PartitionMoveTree.capture(made.catalog.directory(saved.ticket.allocation),requireNotNull(saved.uid),treeOwnership,::live)
            live()
            if(made.legacy.claimsOriginalName(key,permit) || made.session.savedDeleteSource(key,permit,::unclaimed)!=before || art.savedDeleteCover(key,permit)!=cover)
                throw IOException("Saved Delete source changed during snapshot")
            val binding=PartitionSavedDeleteBinding(volume,this.key(directory).toString(),saved.ticket,requireNotNull(saved.uid),
                completed.retirementIdentity(),completed.length,tree,cover)
            val record=made.deletes.begin(binding,true)
            // Durable commit cannot be relabelled by late cancellation.
            permit.check();located();return record
        } catch(failure:Throwable) {
            if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain || failure is SavedCoverCloseUncertain || made.deletes.ownershipUncertain) {
                phase=Phase.Uncertain;permit.quarantine(arrayOf(this,made.deletes,treeOwnership,art,artwork,failure));quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    private var retainedSavedDeleteCleanup:Any?=null
    /** Explicit completion/recovery of the exact durable Delete. Source tree cleanup never opens
     * native over missing files; every mutation rechecks actual source/volume/row/alias/own cover.
     * Known partial deletion stays Pending for renewed recovery; unknown I/O retains real owners. */
    internal fun completeSavedDelete(expected:PartitionSavedDeleteRecord,explicitConsent:Boolean,
        art:DownloadArt,artwork:DownloadArtworkWork,unclaimedElsewhere:(String)->Boolean,
        checkpoint:()->Unit={},other:OwnedPartitionShelf?=null):OfflineStore.SavedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved Delete finish requires a worker"}
        if(!explicitConsent || other===this || other!=null && other.storageBarrier!==barrier) return OfflineStore.SavedRemoval.NotOwned
        if(expected.phase!=PartitionSavedDeletePhase.Pending) return OfflineStore.SavedRemoval.Waiting
        val made=resources();val permit=try {barrier.exclusive()} catch(_:IOException) {return OfflineStore.SavedRemoval.Busy}
        val cleanup=PartitionMoveTreeRemoval();var quarantined=false;var coverMutation=false
        fun owned() {
            permit.check();located()
            if(expected.binding.volume!=volume || expected.binding.rootIdentity!=key(directory).toString())
                throw IOException("Saved Delete source root or volume changed")
            permit.check()
        }
        fun unclaimed(name:String)=other?.claimsMoveName(name,permit)!=true && unclaimedElsewhere(name)
        fun current() {
            owned();checkpoint();owned()
            if(made.legacy.claimsOriginalName(expected.binding.key,permit)) throw IOException("Saved Delete source aliases legacy audio")
            made.session.verifyPendingSavedDelete(expected,permit,::unclaimed)
            artwork.cancelForConfirmedRemoval(expected.binding.key,permit)
            art.verifyMovedSource(expected.binding.key,expected.binding.cover,permit)
            owned()
        }
        try {
            owned()
            if(made.deletes.find(expected.binding.key)!=expected) return OfflineStore.SavedRemoval.Waiting
            current();retainedSavedDeleteCleanup=cleanup
            cleanup.runMoved(expected.binding.tree,made.catalog.directory(expected.binding.ticket.allocation),::current,::current)
            current();made.session.eraseDeletedSavedRecord(expected,permit,::unclaimed)
            current();coverMutation=true
            art.removeSavedDeleteCoverConfirmed(expected.binding.key,expected.binding.cover,permit)
            coverMutation=false
            current();made.session.verifyDeletedSavedRecordAbsent(expected,permit,::unclaimed)
            art.verifyEntryAbsent(expected.binding.key,permit)
            val root=made.catalog.directory(expected.binding.ticket.allocation)
            expected.binding.tree.requireAbsent(root)
            made.deletes.complete(expected)
            // Commit is durable. A late user cancellation cannot relabel successful disposition.
            owned();retainedSavedDeleteCleanup=null;return OfflineStore.SavedRemoval.Sent
        } catch(failure:Throwable) {
            if(coverMutation || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain ||
                failure is SavedCoverCloseUncertain || made.deletes.ownershipUncertain) {
                phase=Phase.Uncertain;permit.quarantine(arrayOf(this,made.deletes,cleanup,art,artwork,failure));quarantined=true
            } else retainedSavedDeleteCleanup=null
            return OfflineStore.SavedRemoval.Failed
        } finally {if(!quarantined) permit.close()}
    }
    /** Fresh ordinary Delete selection only. Existing Pending needs the explicitly shown token
     * recovery path; an unrelated new click cannot silently adopt/replace an old intent. */
    internal fun removeSavedCompleted(key:String,explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,
        unclaimedElsewhere:(String)->Boolean,other:OwnedPartitionShelf?=null):OfflineStore.SavedRemoval {
        if(!explicitConsent) return OfflineStore.SavedRemoval.NotOwned
        return try {
            val selected=recoveryRead {it.saves.find(key) to it.deletes.find(key)}
            if(selected.first==null) OfflineStore.SavedRemoval.NotOwned
            else if(selected.second!=null) OfflineStore.SavedRemoval.Waiting
            else {
                val pending=beginSavedDelete(key,true,art,artwork,unclaimedElsewhere,other=other)
                completeSavedDelete(pending,true,art,artwork,unclaimedElsewhere,other=other)
            }
        } catch(_:PartitionDeleteAdmissionBusy) {OfflineStore.SavedRemoval.Busy}
        catch(_:IOException) {OfflineStore.SavedRemoval.Unavailable}
    }
    /** Renewed exact-key/token Delete recovery supports both new and completed-original sources.
     * A legacy Move or unbound files-only intent cannot authorize original terminal-row removal. */
    internal fun recoverCompletedDelete(key:String,token:String,explicitConsent:Boolean,art:DownloadArt,
        artwork:DownloadArtworkWork,unclaimedElsewhere:(String)->Boolean,other:OwnedPartitionShelf?=null,
        checkpoint:()->Unit={}):OfflineStore.SavedRemoval {
        if(!explicitConsent || other===this || other!=null && other.storageBarrier!==barrier) return OfflineStore.SavedRemoval.NotOwned
        return try {
            val selected=recoveryRead {it.deletes.find(key) to it.retirements.find(key)}
            val saved=selected.first;val original=selected.second
            if(saved!=null && original!=null) throw IOException("Delete has conflicting journals")
            when {
                saved!=null && saved.token==token && saved.phase==PartitionSavedDeletePhase.Pending ->
                    completeSavedDelete(saved,true,art,artwork,unclaimedElsewhere,checkpoint,other)
                original!=null && original.token==token && original.phase==LegacyRetirementPhase.Pending &&
                    original.manifest.moveDestination==null && (original.manifest.completedDownloadIdentity!=null ||
                        original.manifest.retainedDownloadIdentity!=null) ->
                    removeOriginalCompleted(key,true,{name ->unclaimedElsewhere(name)}, {name,permit ->
                        if(other?.claimsMoveName(name,permit)==true) throw IOException("Original Delete acquired another shelf claim")
                        artwork.cancelForConfirmedRemoval(name,permit)
                        val cover=art.savedDeleteCover(name,permit)
                        art.removeSavedDeleteCoverConfirmed(name,cover,permit)
                    },original,other,checkpoint)
                else -> OfflineStore.SavedRemoval.Waiting
            }
        } catch(_:IOException) {OfflineStore.SavedRemoval.Unavailable}
    }
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
    /** Prepared new-source intent only. Source routing is suppressed durably but source bytes,
     * row and own-cover are preserved. No automatic replay or cleanup follows this commit. */
    internal fun beginCoveredSavedMove(destination:OwnedPartitionShelf,copy:PartitionSavedCopy,
        completed:PartitionSaveCompletion,explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,
        checkpoint:()->Unit={}):PartitionSavedMoveRecord {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved Move intent requires a worker"}
        if(!explicitConsent || destination===this || destination.storageBarrier!==barrier)
            throw IOException("Saved Move intent needs consent and actual same-gate owners")
        val source=resources(); val target=destination.resources(); val permit=barrier.exclusive(); var quarantined=false
        val treeOwnership=MigrationIoOwnership()
        fun owned() {permit.check(); located(); destination.located(); permit.check()}
        fun live() {owned(); checkpoint(); owned()}
        try {
            live()
            val old=copy.completion.key; val fresh=copy.destination.allocation.key
            if(copy.source.ticket.allocation.key!=old || source.moves.find(old)!=null ||
                source.retirements.holdsName(old) || claimsMoveName(fresh,permit) || destination.claimsMoveName(old,permit))
                throw IOException("Saved Move intent has conflicting source or destination claims")
            val before=source.session.moveDestinationIdentity(copy.source.ticket,copy.completion,volume,key(directory).toString(),permit,::live)
            if(before.uid!=copy.source.uid) throw IOException("Saved Move source UID differs")
            val after=target.session.moveDestinationIdentity(copy.destination,completed,destination.volume,
                destination.key(destination.directory).toString(),permit,::live)
            val provisional=PartitionSavedMoveBinding(before,after,SavedMoveCoverIdentity(0,null)); provisional.validate()
            artwork.cancelForConfirmedRemoval(old,permit); artwork.cancelForConfirmedRemoval(fresh,permit)
            val cover=art.copyEntryConfirmed(old,fresh,permit)
            source.session.verifyMoveDestination(before,volume,key(directory).toString(),permit,::live)
            target.session.verifyMoveDestination(after,destination.volume,destination.key(destination.directory).toString(),permit,::live)
            art.verifyMovedSource(old,cover,permit); art.verifyMovedTarget(fresh,cover,permit)
            live()
            // Last source native observation is now closed. Do NOT reopen it after capture: native
            // last-touch/index writes would invalidate this exact recovery snapshot.
            val treeRoot=source.catalog.directory(before.ticket.allocation)
            val tree=PartitionMoveTree.capture(treeRoot,before.uid,treeOwnership,::live)
            tree.verifyAudio(treeRoot,before.length,before.audioDigest,treeOwnership,::live)
            live()
            val result=source.moves.begin(provisional.copy(cover=cover,sourceTree=tree),true)
            // Pending is durable: caller cancellation cannot relabel the committed intent.
            owned(); return result
        } catch(failure:Throwable) {
            if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain || failure is SavedCoverCloseUncertain || source.moves.ownershipUncertain || !treeOwnership.quiescent) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,destination,source.moves,art,artwork,treeOwnership,failure)); quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    private var retainedSavedMoveCleanup:Any?=null
    /** Renewed, exact Pending recovery; no automatic replay or generic Delete delegation. Actual
     * target bytes/metadata/row/cover are revalidated before each source tree mutation. */
    internal fun completeCoveredSavedMove(destination:OwnedPartitionShelf,expected:PartitionSavedMoveRecord,
        explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,
        unclaimedElsewhere:(String)->Boolean,checkpoint:()->Unit={}):OfflineStore.SavedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved Move finish requires a worker"}
        if(!explicitConsent || destination===this || destination.storageBarrier!==barrier) return OfflineStore.SavedRemoval.NotOwned
        val tree=expected.binding.sourceTree ?: return OfflineStore.SavedRemoval.Waiting
        if(expected.phase!=PartitionSavedMovePhase.Pending) return OfflineStore.SavedRemoval.Waiting
        val source=resources(); val target=destination.resources()
        val permit=try {barrier.exclusive()} catch(_:IOException) {return OfflineStore.SavedRemoval.Busy}
        var quarantined=false; var coverMutation=false
        val cleanup=PartitionMoveTreeRemoval()
        fun owned() {
            permit.check(); located(); destination.located()
            if(expected.binding.source.volume!=volume || expected.binding.source.rootIdentity!=key(directory).toString())
                throw IOException("Saved Move source volume or root changed")
            permit.check()
        }
        fun live() {owned(); checkpoint(); owned()}
        fun unclaimed(name:String)=!destination.claimsMoveName(name,permit) &&
            !source.legacy.claimsOriginalName(name,permit) && unclaimedElsewhere(name)
        fun current() {live(); source.session.verifyPendingSavedMove(expected,permit,::unclaimed); live()}
        fun verifiedTarget() {
            current()
            val fresh=expected.binding.destination.ticket.allocation.key
            if(claimsMoveName(fresh,permit)) throw IOException("Move destination acquired a source-shelf alias")
            target.session.verifyMoveDestination(expected.binding.destination,destination.volume,
                destination.key(destination.directory).toString(),permit,::live)
            artwork.cancelForConfirmedRemoval(expected.binding.key,permit)
            artwork.cancelForConfirmedRemoval(fresh,permit)
            art.verifyMovedTarget(fresh,expected.binding.cover,permit)
            art.verifyMovedSource(expected.binding.key,expected.binding.cover,permit)
            current()
        }
        try {
            owned()
            if(source.moves.find(expected.binding.key)!=expected) return OfflineStore.SavedRemoval.Waiting
            verifiedTarget()
            val root=source.catalog.directory(expected.binding.source.ticket.allocation)
            retainedSavedMoveCleanup=cleanup
            cleanup.runMoved(tree,root,::current,::verifiedTarget)
            verifiedTarget(); source.session.eraseMovedSavedRecord(expected,permit,::unclaimed)
            verifiedTarget(); coverMutation=true
            art.removeMovedSourceConfirmed(expected.binding.key,expected.binding.destination.ticket.allocation.key,
                expected.binding.cover,permit)
            coverMutation=false
            verifiedTarget(); source.session.verifyMovedSavedRecordAbsent(expected,permit,::unclaimed)
            art.verifyEntryAbsent(expected.binding.key,permit)
            source.moves.complete(expected)
            // Complete is durable. Ignore late user cancellation, but keep actual owner checks.
            owned(); retainedSavedMoveCleanup=null
            return OfflineStore.SavedRemoval.Sent
        } catch(failure:Throwable) {
            if(coverMutation || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain ||
                failure is SavedCoverCloseUncertain || source.moves.ownershipUncertain) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,destination,cleanup,source.moves,art,artwork,failure)); quarantined=true
            } else retainedSavedMoveCleanup=null
            return OfflineStore.SavedRemoval.Failed
        } finally {if(!quarantined) permit.close()}
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
    /** Dispatch by the actual bounded save journal, not key-prefix guesses: an older original
     * may already have a saved/ ID. Existing Pending/Complete intents require explicit recovery. */
    internal fun moveCompletedTo(destination:OwnedPartitionShelf,key:String,newKey:String,
        explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,unclaimedElsewhere:(String)->Boolean,
        checkpoint:()->Unit={},boundary:(PartitionOriginalMoveStage)->Unit={}):PartitionOriginalMoveResult {
        if(!explicitConsent) return PartitionOriginalMoveResult(PartitionOriginalMoveStage.Waiting,OfflineStore.SavedRemoval.NotOwned)
        val newSource=try {recoveryRead {
            if(it.moves.holdsName(key) || it.retirements.holdsName(key) || it.deletes.holdsName(key)) throw IOException("Move needs explicit intent recovery")
            it.saves.find(key)!=null
        }} catch(_:IOException) {return PartitionOriginalMoveResult(PartitionOriginalMoveStage.Waiting,OfflineStore.SavedRemoval.Unavailable)}
        return if(newSource) moveCompletedNewSaveTo(destination,key,newKey,true,art,artwork,unclaimedElsewhere,checkpoint,boundary)
            else moveCompletedOriginalTo(destination,key,newKey,true,art,artwork,unclaimedElsewhere,checkpoint,boundary)
    }
    /** Composed new-source counterpart. Boundaries remain scalar; publication/Pending/Complete
     * are recorded before another cancellation checkpoint. No automatic recovery or extra executor. */
    internal fun moveCompletedNewSaveTo(destination:OwnedPartitionShelf,key:String,newKey:String,
        explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,unclaimedElsewhere:(String)->Boolean,
        checkpoint:()->Unit={},boundary:(PartitionOriginalMoveStage)->Unit={}):PartitionOriginalMoveResult {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Saved Move requires a worker"}
        if(!explicitConsent) return PartitionOriginalMoveResult(PartitionOriginalMoveStage.Waiting,OfflineStore.SavedRemoval.NotOwned)
        var stage=PartitionOriginalMoveStage.Waiting; var bytes=0L
        try {
            checkpoint()
            val copy=copyCompletedSavedTo(destination,key,newKey,true,checkpoint)
            bytes=copy.completion.length; stage=PartitionOriginalMoveStage.Copied; boundary(stage); checkpoint()
            val completed=publishCopiedSavedTo(destination,copy,true,unclaimedElsewhere,checkpoint=checkpoint)
            stage=PartitionOriginalMoveStage.Published; boundary(stage); checkpoint()
            val pending=beginCoveredSavedMove(destination,copy,completed,true,art,artwork,checkpoint=checkpoint)
            stage=PartitionOriginalMoveStage.Pending; boundary(stage); checkpoint()
            val result=completeCoveredSavedMove(destination,pending,true,art,artwork,unclaimedElsewhere,checkpoint)
            if(result==OfflineStore.SavedRemoval.Sent) stage=PartitionOriginalMoveStage.Complete
            return PartitionOriginalMoveResult(stage,result,bytes)
        } catch(failure:Exception) {
            val outcome=if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain ||
                failure is SavedCoverCloseUncertain || !barrier.allowsShared) OfflineStore.SavedRemoval.Unavailable
                else OfflineStore.SavedRemoval.Failed
            return PartitionOriginalMoveResult(stage,outcome,bytes)
        }
    }
    /** One renewed user request selects a key AND exact token, then rereads bounded durable state.
     * The final controller rereads it again under exclusive admission. No automatic restart replay,
     * token guessing, generic Delete delegation or adoption of a different destination shelf. */
    internal fun recoverCompletedMoveTo(destination:OwnedPartitionShelf,key:String,token:String,
        explicitConsent:Boolean,art:DownloadArt,artwork:DownloadArtworkWork,unclaimedElsewhere:(String)->Boolean,
        checkpoint:()->Unit={}):PartitionMoveRecoveryResult {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Move recovery requires a worker"}
        if(!explicitConsent || key.length.toLong()*2>MIGRATION_KEY_BYTES || token.length!=36 || destination===this ||
            destination.storageBarrier!==barrier) return PartitionMoveRecoveryResult(OfflineStore.SavedRemoval.NotOwned)
        return try {
            val selected=recoveryRead {it.moves.find(key) to it.retirements.find(key)}
            val saved=selected.first; val original=selected.second
            if(saved!=null && original!=null) throw IOException("Move recovery has conflicting journals")
            when {
                saved!=null -> {
                    if(saved.token!=token || saved.phase!=PartitionSavedMovePhase.Pending)
                        PartitionMoveRecoveryResult(OfflineStore.SavedRemoval.Waiting)
                    else PartitionMoveRecoveryResult(completeCoveredSavedMove(destination,saved,true,art,artwork,
                        unclaimedElsewhere,checkpoint),saved.binding.destination.ticket.allocation.key,saved.binding.source.length)
                }
                original!=null -> {
                    val bound=original.manifest.moveDestination
                    if(original.token!=token || original.phase!=LegacyRetirementPhase.Pending || bound==null)
                        PartitionMoveRecoveryResult(OfflineStore.SavedRemoval.Waiting)
                    else PartitionMoveRecoveryResult(completeCoveredOriginalMove(destination,original,true,art,artwork,
                        unclaimedElsewhere,checkpoint),bound.ticket.allocation.key,bound.length)
                }
                else -> PartitionMoveRecoveryResult(OfflineStore.SavedRemoval.Waiting)
            }
        } catch(_:IOException) {PartitionMoveRecoveryResult(OfflineStore.SavedRemoval.Unavailable)}
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
        recovery:LegacyRetirementRecord?=null,other:OwnedPartitionShelf?=null,checkpoint:()->Unit={}):OfflineStore.SavedRemoval = try {
        if(other===this || other!=null && other.storageBarrier!==barrier) throw IOException("Original Delete needs same-gate owners")
        val made=resources()
        made.legacy.deleteCompletedOriginal(made.session,made.retirements,key,explicitConsent,recovery,
            unclaimedElsewhere,disposeCover,{located();checkpoint();located()}, {name,permit ->other?.claimsMoveName(name,permit)==true})
    } catch(_:IOException) {OfflineStore.SavedRemoval.Unavailable}
    /** Each journal projects at most two bounded bindings, immediately reduced to scalars.
     * Cursor advances over Delete intents too; they are not offered as Move recovery actions.
     * Kotlin UTF-16 ordering equals the journals' big-endian UTF-16 BLOB key order. */
    internal fun pendingMoveSummaries(after:String?=null):PartitionStorageRecoveryPage = recoveryRead {made ->
        val saved=made.moves.pendingPage(after).map {record ->
            PartitionStorageRecoverySummary(record.binding.key,record.token,record.binding.source.length,PartitionRecoveryAction.Move)
        }
        val originals=made.retirements.pendingPage(after).map {record ->
            val target=record.manifest.moveDestination
            PartitionStorageRecoverySummary(record.manifest.key,record.token,target?.length ?: 0L,if(target!=null) PartitionRecoveryAction.Move else PartitionRecoveryAction.Delete)
        }
        val combined=(saved+originals).sortedBy {it.key}
        if(combined.zipWithNext().any {(left,right) ->left.key==right.key})
            throw IOException("Recovery entries have conflicting journal ownership")
        val taken=combined.take(PARTITION_SAVED_MOVE_PAGE)
        PartitionStorageRecoveryPage(taken.filter {it.action==PartitionRecoveryAction.Move},taken.lastOrNull()?.key,
            combined.size>taken.size || saved.size==PARTITION_SAVED_MOVE_PAGE || originals.size==LEGACY_RETIREMENT_PAGE)
    }
    /** New and original Delete Pending share one bounded key order. Move entries advance the
     * cursor but are never offered as Delete; older unbound files-only intents are explained. */
    internal fun pendingDeleteSummaries(after:String?=null):PartitionStorageRecoveryPage = recoveryRead {made ->
        val saved=made.deletes.pendingPage(after).map {record ->
            PartitionStorageRecoverySummary(record.binding.key,record.token,record.binding.length,PartitionRecoveryAction.Delete)
        }
        val originals=made.retirements.pendingPage(after).map {record ->
            val manifest=record.manifest
            val action=if(manifest.moveDestination!=null) PartitionRecoveryAction.Move
                else if(manifest.completedDownloadIdentity==null && manifest.retainedDownloadIdentity==null) PartitionRecoveryAction.UnsupportedDelete else PartitionRecoveryAction.Delete
            PartitionStorageRecoverySummary(manifest.key,record.token,manifest.spans.sumOf {it.length},action)
        }
        val combined=(saved+originals).sortedBy {it.key}
        if(combined.zipWithNext().any {(left,right) ->left.key==right.key})
            throw IOException("Delete recovery entries have conflicting journal ownership")
        val taken=combined.take(PARTITION_SAVED_DELETE_PAGE)
        PartitionStorageRecoveryPage(taken.filter {it.action!=PartitionRecoveryAction.Move},taken.lastOrNull()?.key,
            combined.size>taken.size || saved.size==PARTITION_SAVED_DELETE_PAGE || originals.size==LEGACY_RETIREMENT_PAGE)
    }
    fun retirementRecoveryPage(after:String?=null):List<LegacyRetirementRecord> = recoveryRead {it.retirements.page(after)}
    fun migrationRecoveryPage(after:String?=null):List<MigrationRecord> = recoveryRead { it.migrations.page(after) }
    fun deleteRecoveryPage(after:String?=null):List<PartitionSavedDeleteRecord> = recoveryRead {it.deletes.page(after)}
    fun moveRecoveryPage(after:String?=null):List<PartitionSavedMoveRecord> = recoveryRead {it.moves.page(after)}
    fun saveRecoveryPage(after:String?=null):List<PartitionSaveRecord> = recoveryRead { it.saves.page(after) }
    private fun <T> recoveryRead(read:(Resources)->T):T {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Recovery reads require a worker" }
        val permit=barrier.shared(); var quarantined=false
        try { val made=resources(); permit.check(); return read(made).also { located(); permit.check() } }
        catch(failure:Throwable) {
            if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain || failure is SavedCoverCloseUncertain) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,failure)); quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
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
