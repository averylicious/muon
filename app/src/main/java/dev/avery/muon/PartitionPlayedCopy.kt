@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** One fresh-copy lifecycle: opportunistic played copy or an unpublished local saved destination. No manager row, migration receipt,
 * restart replay or old-key reuse is fabricated. The actual source must close before native release;
 * an unknown source/native close retains both through the process gate and refuses later work.
 * Journals/native/provider are borrowed from the shelf. Limit/eviction of the WHOLE played library
 * still belongs to its production policy; this bounds each copy and its resident native ownership.
 */
internal class PartitionPlayedCopy(private val catalog:CachePartitionCatalog,
    private val saves:PartitionSaveJournal,private val migrations:CacheMigrationJournal,
    private val native:PartitionNativeOwner,private val barrier:SavedStorageBarrier,
    private val available:()->Boolean,private val unclaimed:(String)->Boolean) {
    private val busy=AtomicBoolean(false)
    @Volatile private var uncertain=false
    private var retained:Any?=null
    fun copy(spec:DataSpec,song:ByteArray,origin:String,upstream:DataSource.Factory,
        writerCreated:(PlayedCopyWriter)->Unit={},limit:()->Long):PartitionSaveTicket {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) { "Played-copy I/O requires a worker" }
        val key=requireNotNull(spec.key)
        if(!key.startsWith(playedKey(NEW_SAVE_PREFIX)) || key.length.toLong()*2>MIGRATION_KEY_BYTES)
            throw IOException("Played copy requires a bounded fresh played-save key")
        val mutations=ContentMetadataMutations().set(SONG_METADATA,song).set(SAVED_FROM_METADATA,origin)
        return copyFresh(spec,mutations,upstream,writerCreated,null,limit)
    }
    /** Preparation for a local saved-copy Move destination. The caller supplies a bounded FULL
     * metadata snapshot and a local-only source, never a lossy playback projection/live fallback.
     * This creates only a clean unpublished partition: no manager completion or source retirement.
     * A later controller still owes byte comparison, durable destination publication and recovery.
     */
    fun copyLocalSaved(spec:DataSpec,metadata:DefaultContentMetadata,source:DataSource.Factory,
        writerCreated:(PlayedCopyWriter)->Unit={}):PartitionSaveTicket {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Local saved copy requires a worker"}
        val key=spec.key ?: throw IOException("Local saved copy requires a fresh key")
        if(!key.startsWith(NEW_SAVE_PREFIX) || key==NEW_SAVE_PREFIX || key.length.toLong()*2>MIGRATION_KEY_BYTES ||
            spec.position!=0L || spec.length<=0 || ContentMetadata.getContentLength(metadata)!=spec.length)
            throw IOException("Local saved copy requires a fresh bounded key and exact full length")
        val known=PartitionResourceCache.measured(metadata,PartitionResourceLimits())
        val mutations=ContentMetadataMutations()
        // Published Media3 byte[] setter copies values. Preserve every admitted field, including
        // unknown optional fields; this is not the display/audio-only legacy projection.
        for((name,value) in known.entrySet()) mutations.set(name,value)
        return copyFresh(spec,mutations,source,writerCreated,known) {spec.length}
    }
    private fun copyFresh(spec:DataSpec,mutations:ContentMetadataMutations,upstream:DataSource.Factory,
        writerCreated:(PlayedCopyWriter)->Unit,expected:DefaultContentMetadata?,limit:()->Long):PartitionSaveTicket {
        val key=requireNotNull(spec.key)
        // Reject oversized metadata before reserving a journal row or native owner.
        val snapshot=PartitionResourceCache.measured(DefaultContentMetadata.EMPTY.copyWithMutationsApplied(mutations),PartitionResourceLimits())
        if(uncertain || !busy.compareAndSet(false,true)) throw IOException("Fresh-copy storage is busy or uncertain")
        var permit:SavedStorageBarrier.Lease?=null
        var cache:Cache?=null
        var writer:PlayedCopyWriter?=null
        var releaseAttempted=false
        var quarantined=false
        fun check() {
            requireNotNull(permit).check()
            if(!available()) throw IOException("Fresh-copy storage unavailable")
        }
        fun unknown(failure:Throwable):Nothing {
            uncertain=true
            val handles=arrayOf(cache,writer,failure)
            retained=handles
            requireNotNull(permit).quarantine(handles); quarantined=true
            throw PartitionOwnershipUncertain(handles,failure)
        }
        try {
            permit=barrier.shared(); check()
            if(catalog.find(key)!=null || saves.find(key)!=null || migrations.find(key)!=null || !unclaimed(key))
                throw IOException("Fresh-copy key is already claimed")
            val ticket=saves.begin(catalog.reserve(key))
            check(); val actual=native.openNewSave(ticket); cache=actual
            actual.applyContentMetadataMutations(key,mutations)
            val source=CacheDataSource.Factory().setCache(actual).setUpstreamDataSourceFactory(upstream)
                .createDataSourceForDownloading()
            if(expected==null) copyPlayedWithinLimit(source,spec,onWriterCreated={ made -> writer=made; writerCreated(made) },limit=limit)
            else PlayedCopyWriter(source,spec,limit).also {made -> writer=made; writerCreated(made)}.cache()
            check()
            val state=savedAudioState(actual,key)
            if(state.coverage!=SavedCoverage.Full || state.bytes<=0)
                throw IOException("Fresh copy did not finish with full bytes")
            if(expected!=null && (state.bytes!=spec.length || state.metadata!=snapshot))
                throw IOException("Local saved copy length or full metadata changed")
            releaseAttempted=true; actual.release(); cache=null
            check()
            return ticket
        } catch(failure:Throwable) {
            if(cache!=null) {
                if(releaseAttempted || failure is PlayedCopyCloseUncertain || failure is PartitionOwnershipUncertain ||
                    failure is MigrationIoUncertain) unknown(failure)
                // Known source closure and this fresh, unpublished played key permit cleaning only
                // this attempt. No older source/target, saved-manager record or cover is removed.
                try {
                    check(); requireNotNull(cache).removeResource(key)
                    releaseAttempted=true; requireNotNull(cache).release(); cache=null
                } catch(cleanup:Throwable) {
                    if(cleanup!==failure) cleanup.addSuppressed(failure)
                    unknown(cleanup)
                }
            } else if(failure is PartitionOwnershipUncertain || failure is MigrationIoUncertain) unknown(failure)
            throw failure
        } finally {
            if(!quarantined) permit?.close()
            busy.set(false)
        }
    }
}
