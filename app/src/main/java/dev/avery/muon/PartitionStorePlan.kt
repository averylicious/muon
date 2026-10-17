package dev.avery.muon

import java.io.File
import java.io.IOException

/** Fixed phone/card startup intent, supplied only after explicit opt-in/recovery has decided Fresh
 * versus Resume and exact volume identity. No filesystem inference, preference repair or fallback.
 * Selection/serialization is still disabled; this is the actual app composition's typed boundary.
 */
internal data class PartitionRootPlan(val directory:File,val legacyDirectory:File,val volume:String,
    val currentVolume:()->String?,val start:PartitionShelfStart,
    /** Fresh volume observation ONLY for LegacyTransitionOwner, which separately rechecks the
     * whole canonical legacy path, root, UID and database. Not partition-root authority. */
    private val legacyVolumeObservation:(()->String?)?=null) {
    // Generic copied plans must follow their current callback, not capture an earlier default.
    val legacyCurrentVolume:()->String? get()=legacyVolumeObservation ?: currentVolume
}
internal data class PartitionStorePlan(val phone:PartitionRootPlan,val card:PartitionRootPlan?=null,
    /** Exact persisted Ready card identity, never a replacement or a claim census. */
    val missingCardVolume:String?=null) {
    fun validate() {
        missingCardVolume?.let {
            if(card!=null || phone.start!=PartitionShelfStart.Resume || it.isBlank() || it.length>512 ||
                it.any {c ->c.isISOControl()} || it==phone.volume)
                throw IOException("Missing card recovery needs an exact distinct Resume identity")
        }
        val roots=listOfNotNull(phone,card)
        for(root in roots) {
            if(root.volume.isBlank() || root.volume.length>512 || root.currentVolume()!=root.volume)
                throw IOException("Selected partition volume unavailable or unsupported")
            for(file in listOf(root.directory,root.legacyDirectory)) {
                if(!file.isAbsolute || file!=file.canonicalFile) throw IOException("Selected partition path is not canonical")
            }
        }
        val paths=roots.flatMap { listOf(it.directory.toPath(),it.legacyDirectory.toPath()) }
        for(i in paths.indices) for(j in 0 until i) {
            if(paths[i].startsWith(paths[j]) || paths[j].startsWith(paths[i]))
                throw IOException("Selected partition/legacy shelves overlap")
        }
    }
}
