package dev.avery.muon

import android.content.Context
import android.os.Environment
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import java.io.IOException

/** Trusted Android app directories only, not persisted/editable paths. Resolve off main before any
 * provider/root allocation. UUID observations are re-read at use; a saved path or old mount-state
 * snapshot never grants access to a replacement card. Missing-card recovery preserves a Ready receipt and pauses all saved mutations; it never
 * infers card claims from an empty download index. This resolver does not activate partition storage. */
internal class PartitionPlatformRoots(
    private val files:()->File,
    private val external:()->Array<File?>,
    private val phoneVolume:(File)->String,
    private val cardVolume:(File)->String?,private val timing:StorageRootTiming?=null) {
    private fun worker() {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Storage root resolution requires a worker"}
    }
    private fun identity(value:String):String {
        if(value.isBlank() || value.length>512 || value.any {it.isISOControl()})
            throw IOException("Storage volume identity is unsupported")
        return value
    }
    private fun folder(file:File):File=file.canonicalFile.also {
        if(!it.isAbsolute || !it.isDirectory) throw IOException("Application storage folder unavailable")
    }
    private fun child(base:File,name:String):File=File(base,name).also {
        if(it!=it.canonicalFile) throw IOException("Selected saved-storage path has an alias")
    }
    private fun phoneNow(base:File):String? {
        if(folder(files())!=base) return null
        val observed=if(timing==null) phoneVolume(base)
            else timing.measure(StorageRootTiming.Phase.PHONE_VOLUME) {phoneVolume(base)}
        return identity(observed)
    }
    private fun currentPhone(base:File):String?=try {
        if(timing==null) phoneNow(base) else timing.measure(StorageRootTiming.Phase.PHONE_ROOT) {phoneNow(base)}
    } catch(_:Exception) {null}
    private fun cardNow(base:File):String? {
        if(!base.isDirectory || base!=base.canonicalFile) return null
        val observed=if(timing==null) cardVolume(base)
            else timing.measure(StorageRootTiming.Phase.CARD_VOLUME) {cardVolume(base)}
        return observed?.let(::identity)
    }
    private fun currentCard(base:File):String?=try {
        if(timing==null) cardNow(base) else timing.measure(StorageRootTiming.Phase.CARD_ROOT) {cardNow(base)}
    } catch(_:Exception) {null}
    /** The actual legacy owner already canonicalizes its whole downloads child at each checkpoint.
     * Avoid canonicalizing the parent a second time; still ask Android for fresh mounted UUID
     * on EVERY call. This observation alone never admits a path, owner, read or mutation. */
    private fun legacyCardVolume(base:File):String?=try {
        val observed=if(timing==null) cardVolume(base)
            else timing.measure(StorageRootTiming.Phase.LEGACY_CARD_VOLUME) {cardVolume(base)}
        observed?.let(::identity)
    } catch(_:Exception) {null}
    /** Only the real partition owner may use this fresh volume-only observation. Its
     * rootLocated() separately validates the full canonical child and pinned root identity. */
    private fun ownedCardVolume(base:File):String?=try {
        val observed=if(timing==null) cardVolume(base)
            else timing.measure(StorageRootTiming.Phase.OWNED_CARD_VOLUME) {cardVolume(base)}
        observed?.let(::identity)
    } catch(_:Exception) {null}
    private fun phone(start:PartitionShelfStart):PartitionRootPlan {
        val base=folder(files());val volume=currentPhone(base) ?: throw IOException("Private storage identity unavailable")
        return PartitionRootPlan(child(base,PARTITION_DIRECTORY),child(base,"downloads"),volume,{currentPhone(base)},start)
    }
    private fun cards():List<Pair<File,String>> {
        val dirs=external()
        if(dirs.size>8) throw IOException("Too many platform storage folders")
        val result=ArrayList<Pair<File,String>>(2)
        for(i in 1 until dirs.size) {
            val candidate=dirs[i] ?: continue
            val base=folder(candidate)
            val volume=currentCard(base) ?: continue
            if(result.any {it.first==base || it.second==volume})
                throw IOException("Ambiguous removable storage identity")
            result+=base to volume
        }
        return result
    }
    private fun card(selected:Pair<File,String>,start:PartitionShelfStart):PartitionRootPlan {
        val (base,volume)=selected
        return PartitionRootPlan(child(base,PARTITION_DIRECTORY),child(base,"downloads"),volume,{currentCard(base)},start,{legacyCardVolume(base)},{ownedCardVolume(base)})
    }
    /** Use only the already-open legacy shelf selected by Android, never a newly inserted/unowned
     * card. Caller still must refuse opt-in while old card records/claims cannot be inspected. */
    fun fresh(currentLegacyCardFolder:File?):PartitionStorePlan {
        worker();val phone=phone(PartitionShelfStart.Fresh);val available=cards()
        val selected=if(currentLegacyCardFolder==null) {
            if(available.isNotEmpty()) throw IOException("Removable storage changed; restart before reviewing opt-in")
            null
        } else {
            val expected=folder(currentLegacyCardFolder)
            available.singleOrNull {it.first==expected} ?: throw IOException("The current saved-copy card is unavailable or changed")
        }
        return PartitionStorePlan(phone,selected?.let {card(it,PartitionShelfStart.Fresh)}).also {it.validate()}
    }
    /** No Fresh/Resume inference from directory existence, UUID substitution or legacy fallback. */
    fun resume(choice:PartitionStartupChoice):PartitionStorePlan {
        worker();val phone=phone(choice.start)
        if(phone.volume!=choice.phoneVolume) throw IOException("Selected private storage identity changed")
        val selected=choice.cardVolume?.let {expected ->cards().singleOrNull {it.second==expected}}
        val missing=choice.cardVolume?.takeIf {selected==null}
        if(missing!=null && (choice.phase!=PartitionStartupPhase.Ready || choice.start!=PartitionShelfStart.Resume))
            throw IOException("Initial or interrupted setup requires the selected SD card")
        return PartitionStorePlan(phone,selected?.let {card(it,choice.start)},missing).also {it.validate()}
    }
    companion object {
        internal const val PARTITION_DIRECTORY="downloads-partition-v1"
        /** API30 adds a public mounted-directory accessor. Unlike getStorageVolume(File), this
         * path comparison does not re-canonicalize every candidate root. Request a NEW volume
         * list on each call; never keep UUID/state/path observations across checkpoints. */
        @androidx.annotation.RequiresApi(30)
        private fun currentVolume30(manager:StorageManager,directory:File):StorageVolume? =
            selectPlatformVolume(directory,manager.storageVolumes) {it.directory}
                ?: manager.getStorageVolume(directory) // Unsupported mount alias/layout: existing path.
        private fun removableVolume(manager:StorageManager,directory:File):String? {
            val volume=if(Build.VERSION.SDK_INT>=30) currentVolume30(manager,directory)
                else manager.getStorageVolume(directory)
            if(volume==null || !volume.isRemovable || volume.isEmulated || volume.state!=Environment.MEDIA_MOUNTED) return null
            return volume.uuid?.let {"removable:$it"}
        }
        fun from(context:Context):PartitionPlatformRoots {
            val app=context.applicationContext
            val manager=app.getSystemService(StorageManager::class.java) ?: throw IOException("Android storage service unavailable")
            return PartitionPlatformRoots({app.filesDir},{app.getExternalFilesDirs(null)},
                {"private:${manager.getUuidForPath(it)}"},
                {directory ->removableVolume(manager,directory)},timing=if(BuildConfig.DEBUG || BuildConfig.STORAGE_TIMINGS) StorageRootTiming.begin(true) else null)
        }
    }
}
