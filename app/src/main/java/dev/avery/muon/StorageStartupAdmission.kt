package dev.avery.muon

import java.io.IOException

/** One process mode, selected before any normal provider/native initialization. Review is only a
 * display/admission mode, not consent to change preferences. Never reset or switch modes live. */
internal class StorageStartupAdmission {
    private enum class Mode { Unselected,Normal,Review }
    private var mode=Mode.Unselected
    @get:Synchronized val reviewing:Boolean get()=mode==Mode.Review
    @Synchronized fun review():Boolean=when(mode) {
        Mode.Unselected -> {mode=Mode.Review;true}
        Mode.Review ->true
        Mode.Normal ->false
    }
    @Synchronized fun normal() {
        if(mode==Mode.Review) throw IOException("Saved storage review owns this process; fully restart Muon for normal startup")
        mode=Mode.Normal
    }
}
