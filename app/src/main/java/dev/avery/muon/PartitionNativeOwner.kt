@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Count opening, open, closing AND quarantined instances. Production uses the process singleton
 * across all stores, pools and migration factories. A separate budget is only for isolated tests.
 * No waiting queue, eviction, reset or retry frees uncertain native ownership. */
internal class PartitionNativeBudget(private val capacity:Int=PARTITION_NATIVE_INSTANCES) {
    init { require(capacity in 1..PARTITION_NATIVE_INSTANCES) }
    companion object { val process=PartitionNativeBudget() }
    private val slots=arrayOfNulls<Permit>(capacity)
    val resident:Int @Synchronized get()=slots.count { it!=null }
    @Synchronized fun acquire():Permit {
        val slot=slots.indexOfFirst { it==null }
        if(slot<0) throw PartitionCacheBusy()
        return Permit(slot).also { slots[slot]=it }
    }
    inner class Permit internal constructor(private val slot:Int) {
        private var ended=false
        private var quarantined=false
        // Strongly keep all possibly live handles; this is bounded by slots, never a growing list.
        private var retained:Any?=null
        fun retain(handles:Any)=synchronized(this@PartitionNativeBudget) { check(!ended); retained=handles }
        fun quarantine(handles:Any)=synchronized(this@PartitionNativeBudget) {
            check(!ended); quarantined=true; retained=handles
        }
        fun closed()=synchronized(this@PartitionNativeBudget) {
            if(ended) return@synchronized
            check(!quarantined) { "Uncertain native ownership cannot return its permit" }
            check(slots[slot]===this); ended=true; slots[slot]=null
        }
    }
}

/** Lifecycle evidence for the supported owned backend, not a generic legacy Cache. */
internal interface PartitionOwnedCache {
    fun checkQuiescent()
    /** Only the actual new-save native owner can establish this lifecycle; generic/Ready caches refuse. */
    fun checkNewSave(key:String,sealed:Boolean=false) { throw IOException("Not an owned new-save lifecycle") }
}

/** UNWIRED owned factory for known private partitions, never a legacy/adopted cache. Each allocation
 * owns bytes/, index/native-v1.db and metadata/content-v1.db as siblings. Public Media3 APIs alone
 * create/update native schemas. Ready opens require the journal UID plus pre-open native and sidecar
 * admission; reservation alone is never routing authority. A migration adapter can create ONE fresh
 * reservation and reopen only that same attempt after a known clean close.
 *
 * The production caller still owes the app-wide source/writer/removal/eviction/availability barrier
 * and all reader/writer/listener pins. A volume token is a fail-closed observation, NOT an OS mount
 * lock, card authentication or power-loss proof. Do not pass independent production budgets or raw
 * native handles elsewhere. No routing, user migration controls or destructive cleanup is enabled.
 */
internal class PartitionNativeOwner(private val catalog:CachePartitionCatalog,
    private val journal:CacheMigrationJournal,private val volume:String,private val currentVolume:()->String?,
    private val limits:PartitionResourceLimits=PartitionResourceLimits(),
    private val budget:PartitionNativeBudget=PartitionNativeBudget.process,
    private val saves:PartitionSaveJournal?=null) {
    init { require(volume.isNotEmpty()) }
    @Volatile private var healthy=true
    private class SaveLifecycle(val journal:PartitionSaveJournal,val ticket:PartitionSaveTicket)
    private class Handles(val permit:PartitionNativeBudget.Permit,val save:SaveLifecycle?) {
        var saveStarted=false
        var database:SQLiteDatabase?=null
        var native:SimpleCache?=null
        var metadata:PartitionContentMetadata?=null
        var facade:PartitionResourceCache?=null
        var attempted=false
    }
    private fun available() {
        if(currentVolume()!=volume) throw IOException("Partition volume unavailable or changed")
    }
    private fun exactDirectory(file:File) {
        if(!file.isDirectory || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Partition directory identity differs")
    }
    private fun exactFile(file:File) {
        if(!file.isFile || file.absoluteFile!=file.canonicalFile || Files.isSymbolicLink(file.toPath()))
            throw IOException("Partition database identity differs")
    }
    /** Read-only routing observation, not a barrier or completion/deletion authority. A later reservation
     * cannot replace or invalidate the verified allocation recorded by Ready. */
    @Synchronized fun readyRoute(key:String):MigrationRecord? {
        if(!healthy) throw IOException("Partition owner stopped after uncertain native ownership")
        available()
        val ready=journal.ready(key) ?: return null
        val uid=requireNotNull(ready.targetUid)
        if(uid==ready.ticket.sourceUid) throw IOException("Ready partition aliases its source UID")
        return ready
    }
    /** Bounded journal page; the caller must filter Ready and recheck identity before exposing it. */
    @Synchronized fun routePage(after:String?):List<MigrationRecord> {
        if(!healthy) throw IOException("Partition owner stopped after uncertain native ownership")
        available(); return journal.page(after)
    }
    @Synchronized fun openReady(key:String):Cache {
        val ready=readyRoute(key) ?: throw IOException("Partition has no verified ready route")
        return open(ready.ticket.allocation,requireNotNull(ready.targetUid),false)
    }
    /** New saves never fabricate migration verification. The caller must already own the exact
     * reservation/request and destructive-command barrier. This does not mean download complete. */
    @Synchronized fun openNewSave(ticket:PartitionSaveTicket):Cache {
        if(!healthy) throw IOException("Partition owner stopped after uncertain native ownership")
        available(); val journal=saves ?: throw IOException("New-save ownership is not configured")
        val row=journal.find(ticket.allocation.key)
        if(row?.ticket!=ticket || row.phase!=PartitionSavePhase.Reserved || row.uid!=null ||
            catalog.find(ticket.allocation.key)!=ticket.allocation || this.journal.find(ticket.allocation.key)!=null)
            throw IOException("New-save reservation differs or collides with migration")
        return open(ticket.allocation,null,true,save=SaveLifecycle(journal,ticket))
    }
    /** A persisted Open/Opening/Uncertain allocation is deliberately not adopted after restart. */
    @Synchronized fun openSaved(key:String):Cache {
        if(!healthy) throw IOException("Partition owner stopped after uncertain native ownership")
        available(); val journal=saves ?: throw IOException("New-save ownership is not configured")
        val row=journal.find(key) ?: throw IOException("Save ownership is missing")
        if(row.phase!=PartitionSavePhase.Closed || catalog.find(key)!=row.ticket.allocation || this.journal.find(key)!=null)
            throw IOException("Save has no clean owned route")
        return open(row.ticket.allocation,requireNotNull(row.uid),false,save=SaveLifecycle(journal,row.ticket))
    }
    /** Return a distinct adapter per migration attempt. It cannot adopt an interrupted old target. */
    fun migrationTarget(key:String):(File)->Cache {
        if(key.length.toLong()*2>limits.keyBytes) throw IOException("Partition key exceeds its budget")
        var allocation:CachePartitionAllocation?=null
        var uid:Long?=null
        var failed=false
        return { directory -> synchronized(this) {
            if(!healthy || failed) throw IOException("Migration factory unavailable")
            available()
            try {
                val first=allocation==null
                val claim=allocation ?: (catalog.find(key) ?: throw IOException("Migration has no reservation"))
                val row=journal.find(key)
                if(row?.ticket?.allocation!=claim || row.phase!=MigrationPhase.Copying ||
                    directory.absoluteFile!=catalog.directory(claim)) throw IOException("Migration target claim differs")
                if(first) allocation=claim
                val cache=open(claim,uid,first,row.ticket.sourceUid)
                if(first) uid=cache.uid
                cache
            } catch(failure:Throwable) { failed=true; throw failure }
        } }
    }
    /** Must be called under this owner's creation mutex; actual native lock also refuses duplicates. */
    private fun open(allocation:CachePartitionAllocation,expectedUid:Long?,fresh:Boolean,forbiddenUid:Long?=null,save:SaveLifecycle?=null):Cache {
        if(allocation.key.length.toLong()*2>limits.keyBytes) throw IOException("Partition key exceeds its budget")
        available()
        val root=catalog.directory(allocation)
        val bytes=File(root,"bytes"); val index=File(root,"index"); val file=File(index,"native-v1.db")
        val metadata=File(root,"metadata")
        val h=Handles(budget.acquire(),save).also { it.permit.retain(it) }
        try {
            if(save!=null) {
                val prior=save.journal.opening(save.ticket); h.saveStarted=true
                if(prior.uid!=expectedUid) throw IOException("Save lifecycle UID changed before open")
            }
            if(fresh) {
                if(root.exists()) throw IOException("Fresh partition already exists")
                val parent=requireNotNull(root.parentFile)
                if(!parent.exists() && !parent.mkdirs()) throw IOException("Partition parent unavailable")
                exactDirectory(parent)
                if(!root.mkdir() || !bytes.mkdir() || !index.mkdir()) throw IOException("Fresh partition layout unavailable")
            }
            exactDirectory(root); exactDirectory(bytes); exactDirectory(index)
            if(SimpleCache.isCacheFolderLocked(bytes)) throw PartitionCacheBusy()
            if(fresh) {
                if(file.exists()) throw IOException("Fresh native database already exists")
                h.database=SQLiteDatabase.openOrCreateDatabase(file,null)
            } else {
                exactFile(file); exactDirectory(metadata); exactFile(File(metadata,"content-v1.db"))
                h.database=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
            }
            val db=requireNotNull(h.database)
            db.execSQL("PRAGMA cache_size=-256"); db.execSQL("PRAGMA synchronous=FULL")
            if(!fresh) {
                val uid=expectedUid ?: throw IOException("Partition UID missing")
                h.metadata=PartitionContentMetadata(metadata,uid,allocation.key)
                PartitionResourceCache.measured(requireNotNull(h.metadata).read(),limits)
                PartitionNativeAdmission.inspect(bytes,uid,allocation.key,db,limits,checkpoint=::available)
            }
            available()
            val provider=object:DatabaseProvider {
                override fun getWritableDatabase()=db
                override fun getReadableDatabase()=db
            }
            // After constructor entry, an exception can leave a background native initializer alive.
            // Do not close its DB or return a residency permit merely because no object was returned.
            h.attempted=true
            val native=SimpleCache(bytes,NoOpCacheEvictor(),provider).also { h.native=it; it.checkInitialization() }
            available()
            if(native.uid<0 || native.uid==forbiddenUid || (!fresh && native.uid!=expectedUid)) throw IOException("Partition native UID changed")
            if(fresh) h.metadata=PartitionContentMetadata(metadata,native.uid,allocation.key,create=true)
            h.facade=PartitionResourceCache.open(native,allocation.key,limits,requireNotNull(h.metadata),::available)
            available()
            save?.let { it.journal.opened(it.ticket,native.uid) }
            available()
            return Owned(h,bytes,metadata,allocation.key,native.uid)
        } catch(failure:Throwable) {
            if(h.saveStarted) markUncertain(h,failure)
            if(h.attempted) quarantine(h,failure)
            else try {
                available(); h.metadata?.close(); h.database?.close(); h.permit.closed()
            } catch(cleanup:Throwable) { quarantine(h,cleanup); if(cleanup!==failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    private fun markUncertain(h:Handles,failure:Throwable) {
        if(!h.saveStarted) return
        try { h.save?.journal?.uncertain(requireNotNull(h.save).ticket) }
        catch(recordFailure:Throwable) { if(recordFailure!==failure) failure.addSuppressed(recordFailure) }
    }
    private fun quarantine(h:Handles,failure:Throwable) { healthy=false; h.permit.quarantine(h); markUncertain(h,failure) }
    private inner class Owned(private val h:Handles,private val bytes:File,private val metadata:File,
        private val key:String,private val nativeUid:Long):Cache by requireNotNull(h.facade),PartitionOwnedCache {
        private var ended=false
        private var uncertain=false
        @Synchronized override fun checkQuiescent() {
            if(ended || uncertain) throw IOException("Partition lifecycle unavailable")
            requireNotNull(h.facade).checkOwnerRelease()
        }
        @Synchronized override fun checkNewSave(key:String,sealed:Boolean) {
            if(uncertain || !healthy || sealed!=ended || key!=this.key)
                throw IOException("New-save native lifecycle differs")
            available()
            val save=h.save ?: throw IOException("Partition was not opened as a new save")
            val row=save.journal.find(key)
            val phase=if(sealed) PartitionSavePhase.Closed else PartitionSavePhase.Open
            if(row?.ticket!=save.ticket || row.phase!=phase || row.uid!=nativeUid ||
                catalog.find(key)!=save.ticket.allocation || journal.find(key)!=null)
                throw IOException("New-save claim changed before completion")
        }
        @Synchronized override fun release() {
            if(ended) return
            if(uncertain) throw IOException("Partition close remains uncertain")
            try {
                available()
                val facade=requireNotNull(h.facade); val native=requireNotNull(h.native); val db=requireNotNull(h.database)
                facade.checkOwnerRelease()
                // Native release prunes stale spans; validate BEFORE permitting that mutation.
                PartitionNativeAdmission.inspect(bytes,nativeUid,key,db,limits,ownedActive=native,checkpoint=::available)
                facade.release()
                available()
                PartitionNativeAdmission.inspect(bytes,nativeUid,key,db,limits,checkpoint=::available)
                // Release can log native persistence failure without throwing. Validate the closed
                // index/layout AND reopen durable metadata; migration still fully rechecks bytes.
                PartitionContentMetadata(metadata,nativeUid,key).use { PartitionResourceCache.measured(it.read(),limits) }
                available(); db.close()
                h.save?.journal?.closed(requireNotNull(h.save).ticket,nativeUid)
                h.permit.closed(); ended=true
            } catch(failure:Throwable) { uncertain=true; quarantine(h,failure); throw failure }
        }
    }
}
