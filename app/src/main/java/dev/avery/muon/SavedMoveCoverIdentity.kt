package dev.avery.muon

import java.io.IOException

/** Bounded receipt for an entry-owned cover, including confirmed absence. It is not deletion
 * authority. A later Move intent must persist this and reobserve the actual destination. */
internal data class SavedMoveCoverIdentity(val length:Long,val digest:String?) {
    fun validate() {
        if(length !in 0..4L*1024*1024 || (length==0L)!=(digest==null) ||
            (digest!=null && (digest.length!=64 || digest.any {it !in '0'..'9' && it !in 'a'..'f'})))
            throw IOException("Moved cover receipt is malformed")
    }
}
