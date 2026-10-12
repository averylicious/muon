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

internal data class OwnedPlayedRemoval(val outcome:OfflineStore.SavedRemoval,val bytes:Long=0,val change:Long?=null)
internal data class OwnedPlayedClear(val removed:Long,val removedBytes:Long,val remainingBytes:Long?,val complete:Boolean)

/** Prepared shelf composition, NOT selected by OfflineStore. The app-level owner must supply ONE
 * shared barrier, the process native budget via native, an exact legacy/index/cover claim census,
 * stable volume observation and exclusively owned journals. Journals, provider and legacy are
 * borrowed: only close them after this session actually closes and all app participants drain.
 * No manager/service/UI switch, legacy-import bound, destructive command or recovery authority.
 */
internal class PartitionStorageSession(private val catalog:CachePartitionCatalog,
    private val saves:PartitionSaveJournal,private val migrations:CacheMigrationJournal,
    private val native:PartitionNativeOwner,private val database:DatabaseProvider,private val indexName:String,
    legacy:SavedAudio,upstream:DataSource.Factory,unclaimed:(String)->Boolean,
    private val available:()->Boolean,private val barrier:SavedStorageBarrier,
    commandCapacity:Int=DOWNLOAD_COMMAND_COUNT,private val retirements:LegacyRetirementJournal?=null):Closeable {
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
    private val mixed=MixedSavedAudio(native,legacy,published,completed) {name -> retirements?.holdsName(name)==true}
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
    private val playedChanges=java.util.concurrent.atomic.AtomicLong()
    private var retainedPlayedOrder:DiskPlayedSpanOrder?=null
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
    private var retainedLocalCopy:Any?=null
    private val localCopyAdmission=java.util.concurrent.atomic.AtomicBoolean(false)
    /** Local destination preparation only, with a byte comparison after writer/source closure.
     * No manager row is fabricated and no source removal is authorized. A mismatch preserves the
     * unpublished Closed destination for explicit disposition; unknown readers/native stay owned.
     */
    internal fun copyLocalSaved(spec:DataSpec,metadata:androidx.media3.datasource.cache.DefaultContentMetadata,
        source:DataSource.Factory,checkpoint:()->Unit):PartitionSaveTicket {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Local saved copy requires a worker"}
        if(!localCopyAdmission.compareAndSet(false,true)) throw IOException("Local saved copy is busy")
        try {return operation {
        writers.trimIdle(); published.trimIdle(); completed.trimIdle(); checkpoint()
        val ticket=playedCopies.copyLocalSaved(spec,metadata,source)
        var cache:Cache?=null; var releasing=false
        val comparison=MoveByteComparison(barrier=barrier)
        fun unknown(failure:Throwable):Nothing {
            val handles=arrayOf(cache,comparison,source,ticket,failure)
            retainedLocalCopy=handles; uncertain=true
            throw PartitionOwnershipUncertain(handles,failure)
        }
        try {
            requireOpen(); checkpoint()
            val closed=saves.find(ticket.allocation.key)
            if(closed?.ticket!=ticket || closed.phase!=PartitionSavePhase.Closed)
                throw IOException("Local copy destination is not the exact Closed attempt")
            val actual=native.openClosedSaveRead(ticket.allocation.key); cache=actual
            fun checked() {
                requireOpen(); checkpoint()
                if(saves.find(ticket.allocation.key)!=closed || actual.uid!=closed.uid)
                    throw IOException("Local destination identity changed")
                val state=savedAudioState(actual,ticket.allocation.key)
                if(state.coverage!=SavedCoverage.Full || state.bytes!=spec.length || state.metadata!=metadata)
                    throw IOException("Local destination no longer has exact bytes/metadata")
            }
            checked()
            if(!comparison.compare(spec,source,LegacySavedAudio(actual).source,
                    {checked(); true},{uncertain=true}))
                throw IOException("Local destination bytes do not match source")
            checked(); releasing=true; actual.release(); cache=null
            requireOpen(); checkpoint(); ticket
        } catch(failure:Throwable) {
            if(comparison.active!=0 || releasing || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain)
                unknown(failure)
            cache?.let {actual ->
                try {actual.release(); cache=null}
                catch(close:Throwable) {if(close!==failure) close.addSuppressed(failure); unknown(close)}
            }
            throw failure
        }
        }} finally {localCopyAdmission.set(false)}
    }
    private var retainedLocalPublication:Any?=null
    /** Publish a byte-verified, exact clean local destination, never via a destructive downloader
     * or an Add token invented after the copy. Source owner holds the SAME exclusive gate and owes
     * its own exact pre/postflight; this publication never authorizes source removal/restart replay.
     */
    internal fun publishLocalSaved(ticket:PartitionSaveTicket,request:DownloadRequest,length:Long,
        metadata:androidx.media3.datasource.cache.DefaultContentMetadata,source:DataSource.Factory,
        permit:SavedStorageBarrier.Lease,checkpoint:()->Unit,unclaimedElsewhere:(String)->Boolean,
        timestamp:Long):PartitionSaveCompletion {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Local publication requires a worker"}
        originalPermit(permit)
        val key=partitionSaveRequestKey(request)
        if(key!=ticket.allocation.key || length<=0 || timestamp<0 ||
            androidx.media3.datasource.cache.ContentMetadata.getContentLength(metadata)!=length)
            throw IOException("Local publication request/length differs")
        PartitionResourceCache.measured(metadata,PartitionResourceLimits())
        val spec=DataSpec.Builder().setUri("muon-saved:local-copy").setKey(key).setLength(length).build()
        var cache:Cache?=null; var releasing=false; var mutated=false
        var index:androidx.media3.exoplayer.offline.DefaultDownloadIndex?=null
        val comparison=MoveByteComparison()
        fun unknown(failure:Throwable):Nothing {
            val handles=arrayOf(this,cache,index,comparison,source,ticket,failure)
            retainedLocalPublication=handles; uncertain=true
            throw PartitionOwnershipUncertain(handles,failure)
        }
        fun live() {originalPermit(permit); checkpoint()}
        try {
            live(); writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            val closed=saves.find(key) ?: throw IOException("Local publication has no Closed attempt")
            fun claims() {
                live()
                if(closed.ticket!=ticket || closed.phase!=PartitionSavePhase.Closed || saves.find(key)!=closed ||
                    catalog.find(key)!=ticket.allocation || migrations.find(key)!=null || retirements?.holdsName(key)==true ||
                    completionIndex.holdsName(key) || !unclaimedElsewhere(key))
                    throw IOException("Local publication has conflicting destination claims")
            }
            claims()
            val actual=native.openClosedSaveRead(key); cache=actual
            fun bytes() {
                claims()
                val state=savedAudioState(actual,key)
                if(actual.uid!=closed.uid || state.coverage!=SavedCoverage.Full || state.bytes!=length || state.metadata!=metadata)
                    throw IOException("Local publication destination bytes/metadata changed")
            }
            bytes()
            if(!comparison.compare(spec,source,LegacySavedAudio(actual).source,{bytes(); true},{}))
                throw IOException("Local publication destination byte comparison failed")
            bytes(); releasing=true; actual.release(); cache=null; releasing=false
            claims()
            val progress=androidx.media3.exoplayer.offline.DownloadProgress().apply {
                bytesDownloaded=length; percentDownloaded=100f
            }
            val record=Download(request,Download.STATE_COMPLETED,timestamp,timestamp,length,0,0,progress)
            val expected=requireNotNull(PartitionSaveCompletion.from(record))
            completionIndex.requireSupportedOrAbsent()
            index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,indexName)
            // The supported index row is published only after both actual comparison readers and
            // the native owner have closed. No full library load or manager task is needed.
            mutated=true; index.putDownload(record)
            live()
            if(completionIndex.find(key)!=expected || completionIndex.holdsOtherName(key) || !unclaimedElsewhere(key))
                throw IOException("Local completed row publication is unconfirmed")
            live(); return expected
        } catch(failure:Throwable) {
            if(mutated || releasing || comparison.active!=0 || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain)
                unknown(failure)
            cache?.let {actual -> try {actual.release(); cache=null}
                catch(close:Throwable) {if(close!==failure) close.addSuppressed(failure); unknown(close)} }
            throw failure
        }
    }
    private var retainedMoveIdentity:Any?=null
    /** Exact destination observation for a future durable Move. Caller owns actual root/volume
     * and the same exclusive gate. Full byte digest is returned only after source/native closure. */
    internal fun moveDestinationIdentity(ticket:PartitionSaveTicket,expected:PartitionSaveCompletion,
        volume:String,rootIdentity:String,permit:SavedStorageBarrier.Lease,checkpoint:()->Unit):PartitionMoveDestination {
        originalPermit(permit)
        val key=ticket.allocation.key
        if(expected.key!=key) throw IOException("Move destination completion belongs to another key")
        var cache:Cache?=null; var releasing=false
        val digest=SavedAudioDigest()
        fun live() {originalPermit(permit); checkpoint()}
        fun unknown(failure:Throwable):Nothing {
            val handles=arrayOf(this,cache,digest,ticket,failure)
            retainedMoveIdentity=handles; uncertain=true
            throw PartitionOwnershipUncertain(handles,failure)
        }
        try {
            live(); writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            val closed=saves.find(key) ?: throw IOException("Move destination has no owned save")
            fun checked() {
                live()
                if(closed.ticket!=ticket || closed.phase!=PartitionSavePhase.Closed || saves.find(key)!=closed ||
                    catalog.find(key)!=ticket.allocation || migrations.find(key)!=null || retirements?.holdsName(key)==true ||
                    completionIndex.find(key)!=expected || completionIndex.holdsOtherName(key))
                    throw IOException("Move destination identity changed")
            }
            checked(); val actual=native.openClosedSaveRead(key); cache=actual
            val state=savedAudioState(actual,key)
            val metadata=PartitionResourceCache.measured(state.metadata,PartitionResourceLimits())
            if(actual.uid!=closed.uid || state.coverage!=SavedCoverage.Full || state.bytes!=expected.length)
                throw IOException("Move destination lacks exact full native bytes")
            val metadataHash=partitionMoveMetadataDigest(metadata)
            val audioHash=digest.read(DataSpec.Builder().setUri("muon-saved:move-identity").setKey(key)
                .setLength(expected.length).build(),LegacySavedAudio(actual).source,::checked)
            checked()
            if(savedAudioState(actual,key).metadata!=metadata) throw IOException("Move destination metadata changed during digest")
            val observed=PartitionMoveDestination(volume,rootIdentity,ticket,actual.uid,expected.retirementIdentity(),
                expected.length,metadataHash,audioHash).also {it.validate()}
            releasing=true; actual.release(); cache=null; releasing=false
            checked(); return observed
        } catch(failure:Throwable) {
            if(releasing || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain) unknown(failure)
            cache?.let {actual -> try {actual.release(); cache=null}
                catch(close:Throwable) {if(close!==failure) close.addSuppressed(failure); unknown(close)} }
            throw failure
        }
    }
    internal fun originalCopyRequest(expected:PartitionSaveCompletion,permit:SavedStorageBarrier.Lease):DownloadRequest {
        originalPermit(permit)
        if(originalCompletion(expected.key,permit,{true})!=expected)
            throw IOException("Original completion changed before local publication")
        return (completionIndex.originalRequest(expected.key,expected) ?: throw IOException("Original request is absent"))
            .also {originalPermit(permit)}
    }
    internal fun originalCopyCompletion(key:String,permit:SavedStorageBarrier.Lease):PartitionSaveCompletion {
        if(!permit.belongsTo(barrier) || permit.exclusive) throw IOException("Original copy requires the same shared gate")
        requireOpen(); permit.check()
        if(retirements?.holdsName(key)==true || saves.find(key)!=null || completionIndex.holdsOtherOriginalName(key))
            throw IOException("Original copy has conflicting retirement/save/index claims")
        val route=migrations.find(key)
        if(route!=null && route.phase!=MigrationPhase.Ready || route==null && catalog.find(key)!=null)
            throw IOException("Original copy has an unverified partition claim")
        return (completionIndex.findOriginal(key) ?: throw IOException("Original copy is not completed")).also {permit.check()}
    }
    /** Explicit deletion of a NEW owned played copy only. Original legacy/Ready migration
     * resources and every manager claim are protected. Exclusive admission proves there is no
     * real reader/writer (including EOF); close idle pools before opening the exact target.
     * Closed remains a durable no-fallback claim even after its own bytes/metadata become empty.
     */
    private var retainedPlayedRemoval:Any?=null
    fun removePlayed(key:String,removable:(String)->Boolean)=removePlayedDetailed(key,removable).outcome
    fun removePlayedDetailed(key:String,removable:(String)->Boolean):OwnedPlayedRemoval =
        removePlayedOwned(key,removable,null)
    private data class PlayedCandidate(val record:PartitionSaveRecord,val touched:Long,val generation:Long)
    private fun touched(cache:Cache,key:String):Long? {
        val spans=cache.getCachedSpans(key)
        if(spans.isEmpty()) return null
        var latest=Long.MIN_VALUE
        for(span in spans) {
            if(span.lastTouchTimestamp<0) throw IOException("Played access time is unsupported")
            latest=maxOf(latest,span.lastTouchTimestamp)
        }
        return latest
    }
    private fun removePlayedOwned(key:String,removable:(String)->Boolean,candidate:PlayedCandidate?):OwnedPlayedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played removal requires a worker" }
        if(!key.startsWith(playedKey(NEW_SAVE_PREFIX)) || key.length.toLong()*2>MIGRATION_KEY_BYTES)
            return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
        if(stopped || closing || uncertain) return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Unavailable)
        val permit=try { barrier.exclusive() } catch(_:IOException) { return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Busy) }
        var cache:Cache?=null; var quarantined=false
        try {
            requireOpen(); permit.check()
            if(candidate!=null && candidate.generation!=playedChanges.get())
                return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Busy)
            val expected=native.savedReadRoute(key)
            if(expected !is PartitionReadRoute.NewSave || expected.record.phase!=PartitionSavePhase.Closed ||
                !removable(key) || completionIndex.holdsName(key)) return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
            if(native.savedReadRoute(key)!=expected || candidate!=null && expected.record!=candidate.record)
                return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
            writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            requireOpen(); permit.check()
            cache=native.openClosedSaveRead(key)
            if(native.savedReadRoute(key)!=expected || cache.uid!=expected.record.uid)
                throw IOException("Played-copy ownership changed before removal")
            if(candidate!=null && touched(cache,key)!=candidate.touched) {
                // A newer real read invalidates the observed LRU candidate. Close exactly once;
                // failure to prove native release is handled by the same retained-owner path.
                cache.release(); cache=null
                return OwnedPlayedRemoval(OfflineStore.SavedRemoval.NotOwned)
            }
            val bytes=cache.getCachedBytes(key,0,Long.MAX_VALUE)
            if(bytes<0) throw IOException("Invalid played-copy byte count")
            cache.removeResource(key)
            permit.check(); cache.release(); cache=null
            requireOpen(); permit.check()
            return OwnedPlayedRemoval(OfflineStore.SavedRemoval.Sent,bytes,playedChanges.incrementAndGet())
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
    /** Fixed-size journal pages and one native resource probe at a time. Observing age never
     * authorizes deletion; the selected Closed identity and newest span timestamp are rechecked
     * under exclusive access. Protected originals/Ready copies and manager aliases are ineligible.
     */
    private fun orderPlayed(order:DiskPlayedSpanOrder,removable:(String)->Boolean,checkpoint:()->Unit)=operation {
        writers.trimIdle(); published.trimIdle(); completed.trimIdle()
        order.initialized()
        var after:String?=null
        while(true) {
            val page=saves.page(after)
            if(page.isEmpty()) break
            for(row in page) {
                checkpoint(); val key=row.ticket.allocation.key
                if(row.phase!=PartitionSavePhase.Closed || !key.startsWith(playedKey(NEW_SAVE_PREFIX)) ||
                    !removable(key) || completionIndex.holdsName(key)) continue
                val route=native.savedReadRoute(key)
                if(route !is PartitionReadRoute.NewSave || route.record!=row) continue
                var cache:Cache?=null; var releasing=false
                try {
                    cache=native.openClosedSaveRead(key)
                    if(cache.uid!=row.uid || native.savedReadRoute(key)!=route) throw IOException("Played age owner changed")
                    val age=touched(cache,key)
                    releasing=true; cache.release(); cache=null
                    requireOpen()
                    if(native.savedReadRoute(key)!=route) throw IOException("Played age route changed")
                    if(age!=null) order.add(androidx.media3.datasource.cache.CacheSpan(key,0,0,age,null))
                } catch(failure:Throwable) {
                    if(cache!=null) {
                        if(!releasing && failure !is PartitionOwnershipUncertain) {
                            try { releasing=true; cache.release(); cache=null }
                            catch(close:Throwable) { if(close!==failure) close.addSuppressed(failure)
                                throw PartitionOwnershipUncertain(arrayOf(cache,close),close) }
                        } else throw PartitionOwnershipUncertain(arrayOf(cache,failure),failure)
                    }
                    throw failure
                }
            }
            after=page.last().ticket.allocation.key
        }
        // A swallowed derived-ledger write error must refuse BEFORE deleting any actual copy.
        order.forEachKey { false }; checkpoint()
    }
    /** Rebuild one disposable disk order, then retain only a scalar census/generation and one
     * candidate. Manual removals change the generation under exclusive access, so interleaving
     * deletion forces a new census rather than over-evicting from an obsolete total. A new writer
     * cannot enter this actor while resizing. Migration preserves logical mixed played bytes.
     */
    fun resizePlayed(limit:Long,removable:(String)->Boolean,checkpoint:()->Unit={}):OwnedPlayedClear {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played resize requires a worker" }
        if(!playedAdmission.compareAndSet(false,true)) return OwnedPlayedClear(0,0,null,false)
        var removed=0L; var bytes=0L; var remaining:Long?=null; var observed=0L
        var order:DiskPlayedSpanOrder?=null
        fun census() {
            repeat(4) {
                val before=playedChanges.get(); val actual=playedBytes(checkpoint)
                if(playedChanges.get()==before) { remaining=actual; observed=before; return }
                checkpoint()
            }
            throw PartitionCacheBusy()
        }
        try {
            census()
            while(true) {
                if(playedChanges.get()!=observed) census()
                if(requireNotNull(remaining)<=limit.coerceAtLeast(0)) return OwnedPlayedClear(removed,bytes,remaining,true)
                if(!barrier.quiescent) return OwnedPlayedClear(removed,bytes,remaining,false)
                if(order==null) {
                    val made=operation {
                        if(retainedPlayedOrder!=null) throw PartitionCacheBusy()
                        val file=catalog.playedOrderFile
                        if(file.absoluteFile!=file.canonicalFile || java.nio.file.Files.isSymbolicLink(file.toPath()) ||
                            file.exists() && !file.isFile) throw IOException("Played order path is not private")
                        DiskPlayedSpanOrder(file).also { retainedPlayedOrder=it; order=it }
                    }
                    orderPlayed(made,removable,checkpoint)
                    if(playedChanges.get()!=observed) census()
                }
                val ledger=requireNotNull(order)
                val next=operation { ledger.oldestObservation() } ?: return OwnedPlayedClear(removed,bytes,remaining,false)
                val row=operation { saves.find(next.first) }
                if(row==null) throw IOException("Played resize claim disappeared")
                checkpoint()
                if(playedChanges.get()!=observed) {census(); continue}
                val result=removePlayedOwned(next.first,removable,PlayedCandidate(row,next.second,observed))
                if(result.outcome!=OfflineStore.SavedRemoval.Sent || result.bytes<=0)
                    return OwnedPlayedClear(removed,bytes,runCatching { playedBytes(checkpoint) }.getOrNull(),false)
                operation { ledger.remove(androidx.media3.datasource.cache.CacheSpan(next.first,0,0,next.second,null)) }
                removed=Math.addExact(removed,1); bytes=Math.addExact(bytes,result.bytes)
                if(result.change==Math.addExact(observed,1)) {
                    remaining=Math.subtractExact(requireNotNull(remaining),result.bytes); observed=requireNotNull(result.change)
                } else census()
            }
        } catch(_:PartitionCacheBusy) { return OwnedPlayedClear(removed,bytes,remaining,false) }
        catch(_:Exception) { return OwnedPlayedClear(removed,bytes,null,false) }
        finally {
            try { order?.let { actual ->
                if(!uncertain) operation {
                    try {actual.close(); retainedPlayedOrder=null}
                    catch(failure:Throwable) {throw PartitionOwnershipUncertain(actual,failure)}
                }
            } } finally {playedAdmission.set(false)}
        }
    }

    /** Explicit Delete for a clean NEW completed manager save only. No destructive manager
     * command is forwarded. Media3 keeps only nonterminal rows in its in-memory task list; once
     * exact COMPLETED ownership and exclusive admission are established, its PUBLIC writable index
     * can remove that terminal record after actual bytes and native release succeed.
     *
     * The caller must exclude other shelves/moves under this same gate. Closed journal/catalog
     * tombstones remain, so deletion cannot expose a legacy fallback or let the ID be reused. A
     * missing/partial owned resource may be explicitly deleted too; it never substitutes another
     * allocation. Unknown I/O or record mutation retains actual owners and stops this session.
     */
    fun removeCompleted(key:String,unclaimedElsewhere:(String)->Boolean):OfflineStore.SavedRemoval {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Completed removal requires a worker" }
        if(!key.startsWith(NEW_SAVE_PREFIX) || key.length.toLong()*2>MIGRATION_KEY_BYTES)
            return OfflineStore.SavedRemoval.NotOwned
        if(stopped || closing || uncertain) return OfflineStore.SavedRemoval.Unavailable
        val permit=try { barrier.exclusive() } catch(_:IOException) { return OfflineStore.SavedRemoval.Busy }
        var cache:Cache?=null; var index:androidx.media3.exoplayer.offline.DefaultDownloadIndex?=null
        var mutated=false; var quarantined=false
        try {
            requireOpen(); permit.check()
            val expected=native.savedReadRoute(key)
            val complete=completionIndex.find(key)
            if(expected !is PartitionReadRoute.NewSave || expected.record.phase!=PartitionSavePhase.Closed ||
                complete==null || completionIndex.holdsOtherName(key) || !unclaimedElsewhere(key))
                return OfflineStore.SavedRemoval.NotOwned
            writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            requireOpen(); permit.check()
            if(native.savedReadRoute(key)!=expected || completionIndex.find(key)!=complete ||
                completionIndex.holdsOtherName(key) || !unclaimedElsewhere(key)) return OfflineStore.SavedRemoval.NotOwned
            cache=native.openClosedSaveRead(key)
            if(cache.uid!=expected.record.uid || native.savedReadRoute(key)!=expected)
                throw IOException("Completed removal ownership changed")
            mutated=true; cache.removeResource(key)
            permit.check(); cache.release(); cache=null
            requireOpen(); permit.check()
            if(native.savedReadRoute(key)!=expected || completionIndex.find(key)!=complete ||
                completionIndex.holdsOtherName(key) || !unclaimedElsewhere(key))
                throw IOException("Completed record changed after byte removal")
            // Published DefaultDownloadIndex.removeDownload deletes by ID without materializing a
            // Download or running Downloader.remove. Never call the wrapped shared index while
            // holding exclusive admission, and never initialize/migrate an unsupported old schema.
            completionIndex.requireSupportedOrAbsent()
            index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,indexName)
            mutated=true; index.removeDownload(key)
            if(completionIndex.containsId(key)) throw IOException("Completed removal record is still present")
            requireOpen(); permit.check()
            return OfflineStore.SavedRemoval.Sent
        } catch(failure:Throwable) {
            if(cache!=null || mutated || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain) {
                val handles=arrayOf(cache,index,writers,published,completed,failure)
                retainedPlayedRemoval=handles; uncertain=true; permit.quarantine(handles); quarantined=true
            }
            return OfflineStore.SavedRemoval.Failed
        } finally { if(!quarantined) permit.close() }
    }
    private fun originalPermit(permit:SavedStorageBarrier.Lease) {
        if(!permit.exclusive || !permit.belongsTo(barrier)) throw IOException("Original removal needs the actual exclusive gate")
        requireOpen(); permit.check()
    }
    /** Exact scalar preflight/recheck under actual exclusive ownership. An absent completed row is
     * allowed only by the final controller's already durable, bound Pending recovery intent. */
    internal fun originalCompletion(key:String,permit:SavedStorageBarrier.Lease,
        unclaimedElsewhere:(String)->Boolean,allowAbsent:Boolean=false):PartitionSaveCompletion? {
        originalPermit(permit)
        if(saves.find(key)!=null || completionIndex.holdsOtherOriginalName(key) || !unclaimedElsewhere(key))
            throw IOException("Original removal has conflicting save/index/shelf claims")
        val route=migrations.find(key)
        if(route!=null && route.phase!=MigrationPhase.Ready || route==null && catalog.find(key)!=null)
            throw IOException("Original removal has an unverified partition claim")
        val current=completionIndex.findOriginal(key)
        if(current==null && (!allowAbsent || completionIndex.containsId(key)))
            throw IOException("Original removal is not an exact completed record")
        originalPermit(permit); return current
    }
    internal fun verifyOriginalCompletion(record:LegacyRetirementRecord,permit:SavedStorageBarrier.Lease,
        unclaimedElsewhere:(String)->Boolean) {
        val bound=record.manifest.completedDownloadIdentity ?: throw IOException("Files-only intent cannot remove a saved record")
        val current=originalCompletion(record.manifest.key,permit,unclaimedElsewhere,true)
        if(current!=null && current.retirementIdentity()!=bound) throw IOException("Original completed record changed after consent")
    }
    internal fun verifyOriginalRecordAbsent(record:LegacyRetirementRecord,permit:SavedStorageBarrier.Lease,
        unclaimedElsewhere:(String)->Boolean) {
        verifyOriginalCompletion(record,permit,unclaimedElsewhere)
        if(completionIndex.containsId(record.manifest.key)) throw IOException("Original saved record reappeared before completion")
        originalPermit(permit)
    }
    /** Terminal public-index mutation only after the source controller proves copies absent/closed.
     * No destructive DownloadManager task and no private native-index write. */
    internal fun eraseOriginalRecord(record:LegacyRetirementRecord,permit:SavedStorageBarrier.Lease,
        unclaimedElsewhere:(String)->Boolean) {
        verifyOriginalCompletion(record,permit,unclaimedElsewhere)
        val key=record.manifest.key
        if(!completionIndex.containsId(key)) return
        val index=androidx.media3.exoplayer.offline.DefaultDownloadIndex(database,indexName)
        try {
            completionIndex.requireSupportedOrAbsent(); index.removeDownload(key)
            originalPermit(permit)
            if(completionIndex.containsId(key) || completionIndex.holdsOtherOriginalName(key) || !unclaimedElsewhere(key))
                throw IOException("Original saved-record removal is unconfirmed")
        } catch(failure:Throwable) {
            val retained=arrayOf(this,index,record,failure); retainedOriginalCleanup=retained; uncertain=true
            throw PartitionOwnershipUncertain(retained,failure)
        }
    }
    private var retainedOriginalCleanup:Any?=null
    /** Internal Ready-duplicate stage, called only by the actual legacy owner with the SAME
     * exclusive permit after exact Pending validation. The tombstone already suppresses routes.
     * Keep the Ready receipt (no ID reuse/fallback); successful return proves only target absence.
     * No download-index/cover removal or Complete transition is supplied by this primitive.
     */
    internal fun retireMigratedOriginal(record:LegacyRetirementRecord,permit:SavedStorageBarrier.Lease) {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Original duplicate cleanup requires a worker"}
        if(!permit.exclusive || !permit.belongsTo(barrier) || record.phase!=LegacyRetirementPhase.Pending)
            throw IOException("Original duplicate cleanup requires the actual exclusive gate and Pending intent")
        requireOpen(); permit.check()
        val key=record.manifest.key
        if(retirements?.find(key)!=record || saves.find(key)!=null)
            throw IOException("Original duplicate cleanup ownership changed")
        val expected=migrations.find(key)
        if(expected==null) {
            if(catalog.find(key)!=null) throw IOException("Original has an unverified partition claim")
            return
        }
        if(expected.phase!=MigrationPhase.Ready || expected.ticket.sourceUid!=record.manifest.uid ||
            native.readyRoute(key)!=expected)
            throw IOException("Original migrated-copy receipt differs or is not Ready")
        var actual:Cache?=null; var mutated=false
        try {
            writers.trimIdle(); published.trimIdle(); completed.trimIdle()
            requireOpen(); permit.check()
            if(retirements?.find(key)!=record || native.readyRoute(key)!=expected || saves.find(key)!=null)
                throw IOException("Original duplicate changed before cleanup")
            actual=native.openReady(key); retainedOriginalCleanup=actual
            if(actual.uid!=expected.targetUid || actual.keys.any {it!=key})
                throw IOException("Original duplicate native identity differs")
            permit.check(); mutated=true; actual.removeResource(key)
            permit.check()
            if(actual.getCachedSpans(key).isNotEmpty() || actual.uid!=expected.targetUid || native.readyRoute(key)!=expected)
                throw IOException("Original duplicate bytes remain or ownership changed")
            actual.release(); actual=null; retainedOriginalCleanup=null
            requireOpen(); permit.check()
            if(retirements?.find(key)!=record || native.readyRoute(key)!=expected)
                throw IOException("Original duplicate disposition changed after close")
        } catch(failure:Throwable) {
            if(actual!=null || mutated || failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain) {
                val retained=arrayOf(this,actual,record,failure); retainedOriginalCleanup=retained; uncertain=true
                throw PartitionOwnershipUncertain(retained,failure)
            }
            throw failure
        }
    }
    fun prepare(request:DownloadRequest)=operation {
        commands.reconcileCompleted(completionIndex::find)
        commands.prepare(request)
    }
    /** Complete scalar name ownership for another app shelf; no metadata hydration or whole-key set.
     * Pending/uncertain/empty journal claims still reserve their names, not merely playable copies.
     */
    fun claimsName(name:String):Boolean=operation {
        retirements?.holdsName(name)==true || catalog.find(name)!=null || saves.find(name)!=null || migrations.find(name)!=null ||
            completionIndex.holdsName(name) || mixed.contains(name)
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
        operation {
            if(retirements?.holdsName(key)==true) throw IOException("Migration cannot replace retired original intent")
        }
        val publication=CacheMigrationPublication(catalog,migrations,native.migrationTarget(key))
        val adapter=BarrierCacheMigration(barrier) {
            requireOpen(); writers.trimIdle(); published.trimIdle(); completed.trimIdle()
        }
        return try { adapter.run(control,source,key,publication,availableBytes) {
            requireOpen()
            if(saves.find(key)!=null) throw IOException("Migration cannot replace a new-save claim")
            if(retirements?.holdsName(key)==true) throw IOException("Migration cannot replace retired original intent")
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
        operation {
            if(retirements?.holdsName(key)==true) throw IOException("Migration cannot replace retired original intent")
        }
        val publication=CacheMigrationPublication(catalog,migrations,native.migrationTarget(key))
        val adapter=BarrierCacheMigration(barrier) {
            requireOpen(); writers.trimIdle(); published.trimIdle(); completed.trimIdle()
        }
        return try {
            adapter.runProjected(control,key,publication,availableBytes,{
                requireOpen()
                if(saves.find(key)!=null) throw IOException("Migration cannot replace a new-save claim")
                if(retirements?.holdsName(key)==true) throw IOException("Migration cannot replace retired original intent")
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
            if(playedAdmission.get()) throw IOException("Played storage work must finish before close")
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
