@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Own the read-only legacy connection BEFORE opening a partition shelf. Register this owner in
 * StorageStartup before open(). All writers, readers, migration and eventual teardown must share
 * this gate; a folder-lock observation alone is not exclusion. No SimpleCache, DB creation/repair,
 * implicit fallback, legacy deletion, hot service-manager swap or full-index Java load.
 * Unsupported layouts remain intact. The caller still owes actual production backend selection.
 */
internal class LegacyTransitionOwner(private val directory:File,private val databaseFile:File,
    private val barrier:SavedStorageBarrier,private val available:()->Boolean,
    private val openDatabase:(File)->SQLiteDatabase={ SQLiteDatabase.openDatabase(it.path,null,SQLiteDatabase.OPEN_READONLY) },
    private val closeDatabase:(SQLiteDatabase)->Unit={ it.close() },
    private val directoryIo:MigrationIoOwnership=MigrationIoOwnership()):Closeable {
    private enum class Phase { New,Opening,Open,Closing,Closed,Unsupported,Uncertain }
    @Volatile private var phase=Phase.New
    private var index:SQLiteDatabase?=null
    private var identity:Any?=null
    private var rootIdentity:Any?=null
    private var markerIdentity:Any?=null
    private var uid:Long?=null
    private var audio:SavedAudio?=null
    private var retirement:LegacyRetirementFiles?=null

    private fun observed() {
        if(!available() || !directory.isDirectory || directory.absoluteFile!=directory.canonicalFile ||
            Files.isSymbolicLink(directory.toPath()) || SimpleCache.isCacheFolderLocked(directory))
            throw IOException("Legacy storage is unavailable or still has a native owner")
        val rootNow=Files.readAttributes(directory.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
            ?: throw IOException("Legacy directory identity cannot be established")
        if(rootIdentity==null) rootIdentity=rootNow else if(rootIdentity!=rootNow) throw IOException("Legacy root was replaced")
        uid?.let { value ->
            val marker=File(directory,java.lang.Long.toHexString(value)+".uid").toPath()
            val attributes=Files.readAttributes(marker,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(!attributes.isRegularFile || attributes.size()!=0L) throw IOException("Legacy UID marker changed")
            val now=attributes.fileKey() ?: throw IOException("Legacy marker identity cannot be established")
            if(markerIdentity==null) markerIdentity=now else if(markerIdentity!=now) throw IOException("Legacy UID marker was replaced")
        }
        if(!databaseFile.isFile || databaseFile.absoluteFile!=databaseFile.canonicalFile ||
            Files.isSymbolicLink(databaseFile.toPath())) throw IOException("Legacy database identity unavailable")
        val now=Files.readAttributes(databaseFile.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
            ?: throw IOException("Legacy database file identity cannot be established")
        if(identity==null) identity=now else if(identity!=now) throw IOException("Legacy database file was replaced")
    }
    private fun checkpoint() {
        if(phase!=Phase.Opening && phase!=Phase.Open) throw IOException("Legacy transition owner is closed or uncertain")
        observed()
    }
    private fun discoverUid():Long {
        var found:Long?=null; var count=0
        directoryIo.directory(directory.toPath()).use { entries -> for(path in entries) {
            checkpoint()
            if(++count>11 || Files.isSymbolicLink(path)) throw IOException("Unsupported legacy root layout")
            val name=path.fileName.toString()
            if(name.length==1 && name[0] in '0'..'9' && Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) continue
            if(!name.endsWith(".uid") || name.length>20 || found!=null ||
                !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=0L)
                throw IOException("Unsupported legacy UID marker")
            val hex=name.removeSuffix(".uid")
            val parsed=hex.toLongOrNull(16)
            if(parsed==null || parsed<0 || java.lang.Long.toHexString(parsed)!=hex)
                throw IOException("Noncanonical legacy UID marker")
            found=parsed
        } }
        return found ?: throw IOException("Legacy UID marker missing; no native cache will be created")
    }

    /** Must run off-main in the eventual production startup/migration worker. No retry after failed
     * startup on this owner, even if its known connection close succeeded. Nothing adopts a new UID.
     */
    @Synchronized fun open():SavedAudio {
        if(phase==Phase.Open) return requireNotNull(audio)
        if(phase!=Phase.New) throw IOException("Legacy transition startup cannot be retried on this owner")
        val permit=barrier.exclusive(); var quarantined=false
        phase=Phase.Opening
        try {
            checkpoint(); val found=discoverUid(); uid=found
            index=openDatabase(databaseFile)
            if(!requireNotNull(index).isReadOnly) throw IOException("Legacy connection must be read-only")
            LegacyResourceProjection.validate(directory,found,requireNotNull(index),::checkpoint,directoryIo)()
            audio=ReadOnlyLegacySavedAudio(directory,found,requireNotNull(index),barrier,::checkpoint)
            phase=Phase.Open
            return requireNotNull(audio)
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || !directoryIo.quiescent) {
                phase=Phase.Uncertain; permit.quarantine(this); quarantined=true
            } else {
                try { index?.let(closeDatabase); index=null; phase=Phase.Unsupported }
                catch(cleanup:Throwable) {
                    phase=Phase.Uncertain; permit.quarantine(this); quarantined=true
                    if(cleanup!==failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        } finally { if(!quarantined) permit.close() }
    }

    /** Borrow the same live connection for an exclusive migration in the SAME session/gate. Ready
     * still grants routing only, never source deletion. Reader-at-EOF prevents this acquisition.
     */
    @Synchronized fun migrate(session:PartitionStorageSession,control:CacheMigrationControl,key:String,
        availableBytes:()->Long,checked:()->Unit):MigrationRecord {
        checkpoint()
        if(!session.usesBarrier(barrier)) throw IOException("Legacy migration requires the owner's shared storage gate")
        return session.migrateLegacy(control,directory,requireNotNull(uid),key,requireNotNull(index),availableBytes) {
            checkpoint(); checked(); checkpoint()
        }
    }
    /** Prepared copy from the actual closed legacy owner, with full metadata and local-only
     * remapping to a fresh destination key. The same shared gate pins both shelves against removal.
     * Returned scalar observation is NOT durable Move/deletion authority; publication and exact
     * destination-bound recovery still belong to the later final controller.
     */
    @Synchronized fun copyCompletedOriginalTo(session:PartitionStorageSession,destination:PartitionStorageSession,
        key:String,newKey:String,explicitConsent:Boolean,checked:()->Unit):PartitionOriginalCopy {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original copy requires a worker"}
        if(!explicitConsent || session===destination || !session.usesBarrier(barrier) || !destination.usesBarrier(barrier))
            throw IOException("Original copy needs consent, another shelf and the same gate")
        checkpoint()
        val permit=barrier.shared(); var quarantined=false
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            live(); val completion=session.originalCopyCompletion(key,permit)
            val projected=LegacyResourceProjection.read(directory,requireNotNull(uid),key,requireNotNull(index),::live,directoryIo)
            val state=savedAudioState(projected,key)
            val metadata=PartitionResourceCache.measured(state.metadata,PartitionResourceLimits())
            if(state.coverage!=SavedCoverage.Full || state.bytes!=completion.length)
                throw IOException("Original completed record does not match full local audio")
            val spec=DataSpec.Builder().setUri("muon-saved:local-copy").setKey(newKey).setLength(completion.length).build()
            val source=DataSource.Factory {
                val child=savedCacheReader(projected)
                object:DataSource by child {
                    override fun open(incoming:DataSpec):Long {
                        live()
                        if(incoming.key!=newKey) throw IOException("Original copy remapping key changed")
                        return child.open(incoming.buildUpon().setKey(key).build()).also {live()}
                    }
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                        live(); return child.read(bytes,offset,length).also {live()}
                    }
                }
            }
            val ticket=destination.copyLocalSaved(spec,metadata,source,::live)
            live()
            if(session.originalCopyCompletion(key,permit)!=completion)
                throw IOException("Original completed record changed while copying")
            val after=LegacyResourceProjection.read(directory,requireNotNull(uid),key,requireNotNull(index),::live,directoryIo)
            val final=savedAudioState(after,key)
            if(final.coverage!=SavedCoverage.Full || final.bytes!=completion.length || final.metadata!=metadata)
                throw IOException("Original full metadata/coverage changed while copying")
            live(); return PartitionOriginalCopy(completion,ticket)
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain || !directoryIo.quiescent) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,destination,failure)); quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    /** Explicit publication after copy preparation, still preserving every source. This is not
     * a durable Move receipt; a final controller must bind the destination before any retirement.
     * Recompare both actual copies under the SAME exclusive gate rather than trusting old success.
     */
    @Synchronized fun publishCopiedOriginalTo(session:PartitionStorageSession,destination:PartitionStorageSession,
        copy:PartitionOriginalCopy,explicitConsent:Boolean,unclaimedElsewhere:(String)->Boolean,
        checked:()->Unit,timestamp:Long):PartitionSaveCompletion {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original publication requires a worker"}
        if(!explicitConsent || session===destination || !session.usesBarrier(barrier) || !destination.usesBarrier(barrier))
            throw IOException("Original publication needs consent, another shelf and the same gate")
        checkpoint()
        val permit=barrier.exclusive(); var quarantined=false
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            live()
            val old=session.originalCopyRequest(copy.source,permit)
            val key=copy.destination.allocation.key
            val request=androidx.media3.exoplayer.offline.DownloadRequest.Builder(key,old.uri).setMimeType(old.mimeType)
                .setCustomCacheKey(key).setData(old.data).build()
            val projected=LegacyResourceProjection.read(directory,requireNotNull(uid),copy.source.key,requireNotNull(index),::live,directoryIo)
            val state=savedAudioState(projected,copy.source.key)
            val metadata=PartitionResourceCache.measured(state.metadata,PartitionResourceLimits())
            if(state.coverage!=SavedCoverage.Full || state.bytes!=copy.source.length)
                throw IOException("Original is no longer full before local publication")
            val source=DataSource.Factory {
                val child=savedCacheReader(projected)
                object:DataSource by child {
                    override fun open(spec:DataSpec):Long {
                        live(); if(spec.key!=key) throw IOException("Publication source remapping changed")
                        return child.open(spec.buildUpon().setKey(copy.source.key).build()).also {live()}
                    }
                    override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                        live(); return child.read(bytes,offset,length).also {live()}
                    }
                }
            }
            val published=destination.publishLocalSaved(copy.destination,request,copy.source.length,metadata,source,
                permit,::live,unclaimedElsewhere,timestamp)
            live()
            if(session.originalCompletion(copy.source.key,permit,{true})!=copy.source)
                throw IOException("Original record changed after destination publication; source retained")
            live(); return published
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain || !directoryIo.quiescent) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,destination,failure)); quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    /** Persist destination-bound Move intent only after current source and actual destination
     * byte digests match under the same exclusive gate. No source file/row/cover is removed here.
     * Generic Delete/files-only recovery refuses this binding until a target-aware finisher exists.
     */
    @Synchronized fun beginMovedRetirement(session:PartitionStorageSession,journal:LegacyRetirementJournal,
        copy:PartitionOriginalCopy,explicitConsent:Boolean,
        observeDestination:(SavedStorageBarrier.Lease)->PartitionMoveDestination,
        observeCover:((SavedStorageBarrier.Lease)->SavedMoveCoverIdentity)?=null,
        recovery:LegacyRetirementRecord?=null,checked:()->Unit):LegacyRetirementRecord {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Move intent requires a worker"}
        if(!explicitConsent || !session.usesBarrier(barrier)) throw IOException("Move intent needs consent and the same gate")
        checkpoint(); val permit=barrier.exclusive(); var quarantined=false
        val digest=SavedAudioDigest()
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            live()
            if(session.originalCompletion(copy.source.key,permit,{true})!=copy.source)
                throw IOException("Original changed before Move intent")
            val projected=LegacyResourceProjection.read(directory,requireNotNull(uid),copy.source.key,requireNotNull(index),::live,directoryIo)
            val state=savedAudioState(projected,copy.source.key)
            if(state.coverage!=SavedCoverage.Full || state.bytes!=copy.source.length)
                throw IOException("Move original no longer has exact full bytes")
            val original=LegacyRetirementManifest.capture(directory,requireNotNull(uid),copy.source.key,requireNotNull(index),::live,directoryIo)
                .withCompletedDownload(copy.source)
            val audioHash=digest.read(DataSpec.Builder().setUri("muon-saved:move-original").setKey(copy.source.key)
                .setLength(copy.source.length).build(),LegacySavedAudio(projected).source,::live)
            val target=observeDestination(permit)
            if(target.ticket!=copy.destination || target.length!=copy.source.length || target.audioDigest!=audioHash ||
                target.metadataDigest!=partitionMoveMetadataDigest(PartitionResourceCache.measured(state.metadata,PartitionResourceLimits())))
                throw IOException("Move destination no longer matches original audio")
            val audioBound=original.withMoveDestination(target)
            if(recovery!=null && (observeCover==null || recovery.phase!=LegacyRetirementPhase.Pending ||
                recovery.manifest.moveCover!=null || recovery.manifest!=audioBound || journal.find(copy.source.key)!=recovery))
                throw IOException("Move cover recovery requires the exact fully present original and Pending intent")
            val cover=observeCover?.invoke(permit)
            val manifest=if(cover==null) audioBound else audioBound.withMoveCover(cover)
            manifest.verifyRemaining(directory,requireNotNull(index),false,::live,directoryIo)
            live()
            if(session.originalCompletion(copy.source.key,permit,{true})!=copy.source)
                throw IOException("Original completed record changed before durable Move intent")
            val record=if(recovery==null) journal.begin(manifest,true)
                else journal.bindMoveCover(recovery,requireNotNull(cover))
            // The durable commit is past its cancellation boundary. Do not run an external
            // cancellation callback now and misreport a recorded Pending as mere publication.
            permit.check(); checkpoint(); return record
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain || failure is SavedCoverCloseUncertain || journal.ownershipUncertain || !directoryIo.quiescent) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,journal,digest,observeDestination,failure)); quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    /** Persist explicit cleanup intent while the actual legacy owner/volume and all app readers
     * are excluded. No file/index deletion is performed here; the controller must complete it.
     * Unknown manifest I/O or journal write retains both real owners and invalidates this gate.
     */
    @Synchronized fun beginRetirement(session:PartitionStorageSession,journal:LegacyRetirementJournal,
        key:String,explicitConsent:Boolean,checked:()->Unit):LegacyRetirementRecord {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original retirement requires a worker"}
        if(!explicitConsent) throw IOException("Original retirement requires explicit consent")
        if(!session.usesBarrier(barrier)) throw IOException("Original retirement requires the same storage gate")
        checkpoint()
        val permit=barrier.exclusive(); var quarantined=false
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            live()
            val manifest=LegacyRetirementManifest.capture(directory,requireNotNull(uid),key,requireNotNull(index),::live)
            live(); val record=journal.begin(manifest,true); live(); return record
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain || journal.ownershipUncertain) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,journal,failure)); quarantined=true
            }
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    /** Internal physical-original stage only. The exact durable Pending record and consent are
     * required again on restart; no automatic replay. Other copies/rows/artwork remain the final
     * controller's responsibility, so the journal intentionally stays Pending after this returns.
     */
    @Synchronized fun retirePendingFiles(session:PartitionStorageSession,journal:LegacyRetirementJournal,
        expected:LegacyRetirementRecord,explicitConsent:Boolean,checked:()->Unit,
        makeFiles:()->LegacyRetirementFiles={LegacyRetirementFiles()}):LegacyRetirementFileResult {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original file cleanup requires a worker"}
        if(!explicitConsent || expected.phase!=LegacyRetirementPhase.Pending || expected.manifest.moveDestination!=null || !session.usesBarrier(barrier))
            throw IOException("Original file cleanup requires exact consent and the shared gate")
        checkpoint()
        val permit=barrier.exclusive(); var quarantined=false
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            live()
            if(journal.find(expected.manifest.key)!=expected) throw IOException("Original retirement intent changed")
            session.retireMigratedOriginal(expected,permit)
            live()
            val actual=makeFiles(); retirement=actual // No constructor I/O; own before run.
            val result=actual.run(expected,directory,requireNotNull(index),::live)
            live(); retirement=null; return result
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain ||
                journal.ownershipUncertain || retirement?.uncertain==true) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,journal,retirement,failure)); quarantined=true
            } else retirement=null
            throw failure
        } finally {if(!quarantined) permit.close()}
    }
    /** Complete internal Delete for a supported completed original. Bind its exact row BEFORE
     * durable intent; after restart only the exact shown Pending record plus renewed consent can
     * recover. Complete follows both copy stages, public terminal-index removal and confirmed own
     * cover disposition. No automatic replay or full legacy native-index allocation.
     */
    @Synchronized fun deleteCompletedOriginal(session:PartitionStorageSession,journal:LegacyRetirementJournal,
        key:String,explicitConsent:Boolean,recovery:LegacyRetirementRecord?,unclaimedElsewhere:(String)->Boolean,
        disposeCover:(String,SavedStorageBarrier.Lease)->Unit,checked:()->Unit):OfflineStore.SavedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original Delete requires a worker"}
        if(!explicitConsent || !session.usesBarrier(barrier)) return OfflineStore.SavedRemoval.NotOwned
        checkpoint()
        val permit=try {barrier.exclusive()} catch(_:IOException) {return OfflineStore.SavedRemoval.Busy}
        var quarantined=false; var finalMutation=false
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            live()
            val existing=journal.find(key)
            val pending=if(existing==null) {
                if(recovery!=null) return OfflineStore.SavedRemoval.NotOwned
                val completion=requireNotNull(session.originalCompletion(key,permit,unclaimedElsewhere))
                val manifest=LegacyRetirementManifest.capture(directory,requireNotNull(uid),key,requireNotNull(index),::live)
                    .withCompletedDownload(completion)
                live(); journal.begin(manifest,true)
            } else {
                if(recovery!=existing || existing.phase!=LegacyRetirementPhase.Pending)
                    return OfflineStore.SavedRemoval.Waiting
                existing
            }
            if(pending.manifest.moveDestination!=null) return OfflineStore.SavedRemoval.Waiting
            session.verifyOriginalCompletion(pending,permit,unclaimedElsewhere); live()
            session.retireMigratedOriginal(pending,permit); live()
            val files=LegacyRetirementFiles(); retirement=files
            files.run(pending,directory,requireNotNull(index),::live)
            live(); finalMutation=true
            session.eraseOriginalRecord(pending,permit,unclaimedElsewhere)
            live(); disposeCover(key,permit); live()
            session.verifyOriginalRecordAbsent(pending,permit,unclaimedElsewhere)
            if(journal.find(key)!=pending) throw IOException("Original deletion intent changed before completion")
            journal.complete(pending); live(); retirement=null
            return OfflineStore.SavedRemoval.Sent
        } catch(failure:Throwable) {
            if(finalMutation || failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain ||
                journal.ownershipUncertain || retirement?.uncertain==true) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,journal,retirement,disposeCover,failure)); quarantined=true
            } else retirement=null
            return OfflineStore.SavedRemoval.Failed
        } finally {if(!quarantined) permit.close()}
    }
    /** Complete only an exact shown covered Move Pending, with renewed consent. Destination
     * bytes/UID/completion/root AND persisted own-cover proof are checked before every unlink and
     * again before Complete. Missing originals may recover only under this exact durable intent.
     * No generic Delete, automatic replay, same-ID reuse or full old native-index allocation. */
    @Synchronized internal fun completeMovedOriginal(session:PartitionStorageSession,journal:LegacyRetirementJournal,
        expected:LegacyRetirementRecord,explicitConsent:Boolean,unclaimedElsewhere:(String)->Boolean,
        verifyDestination:(SavedStorageBarrier.Lease)->Unit,
        disposeCover:(String,SavedStorageBarrier.Lease)->Unit,checked:()->Unit):OfflineStore.SavedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original Move finish requires a worker"}
        if(!explicitConsent || !session.usesBarrier(barrier)) return OfflineStore.SavedRemoval.NotOwned
        if(expected.phase!=LegacyRetirementPhase.Pending || expected.manifest.moveDestination==null || expected.manifest.moveCover==null)
            return OfflineStore.SavedRemoval.Waiting
        checkpoint()
        val permit=try {barrier.exclusive()} catch(_:IOException) {return OfflineStore.SavedRemoval.Busy}
        var quarantined=false; var finalMutation=false
        try {
            fun live() {permit.check(); checkpoint(); checked(); checkpoint()}
            fun target() {live(); verifyDestination(permit); live()}
            live()
            if(journal.find(expected.manifest.key)!=expected) return OfflineStore.SavedRemoval.Waiting
            session.verifyOriginalCompletion(expected,permit,unclaimedElsewhere); target()
            session.retireMigratedOriginal(expected,permit); target()
            val files=LegacyRetirementFiles(); retirement=files
            files.runMoved(expected,directory,requireNotNull(index),::live,::target)
            target(); finalMutation=true
            session.eraseOriginalRecord(expected,permit,unclaimedElsewhere)
            target(); disposeCover(expected.manifest.key,permit); target()
            session.verifyOriginalRecordAbsent(expected,permit,unclaimedElsewhere)
            if(journal.find(expected.manifest.key)!=expected) throw IOException("Move intent changed before completion")
            journal.complete(expected)
            // Complete is durable; caller cancellation cannot relabel a completed Move Pending.
            permit.check(); checkpoint(); retirement=null
            return OfflineStore.SavedRemoval.Sent
        } catch(failure:Throwable) {
            if(finalMutation || failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain ||
                failure is SavedCoverCloseUncertain || journal.ownershipUncertain || retirement?.uncertain==true) {
                phase=Phase.Uncertain; permit.quarantine(arrayOf(this,journal,retirement,verifyDestination,disposeCover,failure)); quarantined=true
            } else retirement=null
            return OfflineStore.SavedRemoval.Failed
        } finally {if(!quarantined) permit.close()}
    }
    @Synchronized override fun close() {
        if(phase==Phase.Closed || phase==Phase.Unsupported || phase==Phase.New) { phase=Phase.Closed; return }
        if(phase!=Phase.Open) throw IOException("Legacy transition close is active or uncertain")
        val permit=barrier.exclusive(); var quarantined=false
        phase=Phase.Closing
        try { index?.let(closeDatabase); index=null; phase=Phase.Closed }
        catch(failure:Throwable) {
            phase=Phase.Uncertain; permit.quarantine(this); quarantined=true; throw failure
        } finally { if(!quarantined) permit.close() }
    }
}

/** Preparation receipt only: neither a persisted destination completion nor source deletion consent. */
internal data class PartitionOriginalCopy(val source:PartitionSaveCompletion,val destination:PartitionSaveTicket)
