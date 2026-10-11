package dev.avery.muon

import java.io.IOException
import java.io.OutputStream
import okhttp3.Response
import okio.BufferedSource

/** One actual saved-cover fetch. Never use Response.close here: pinned OkHttp quietly suppresses
 * checked source-close errors. Keep the exact response/source/output when any close is unknown.
 * Each participant gets one close attempt; a later finish cannot retry or erase uncertainty. */
internal class SavedCoverIo {
    var response:Response?=null
    var source:BufferedSource?=null
    var output:OutputStream?=null
    private var closing=false
    private var finished=false
    private var uncertain=false
    fun finish() {
        if(uncertain || closing) { uncertain=true; throw SavedCoverCloseUncertain(this,null) }
        if(finished) return
        closing=true
        var failure:Throwable?=null
        try {
            try { output?.close(); if(!uncertain) output=null }
            catch(error:Throwable) { uncertain=true; failure=error }
            try { source?.close(); if(!uncertain) source=null }
            catch(error:Throwable) {
                uncertain=true
                if(failure==null) failure=error else if(failure!==error) failure!!.addSuppressed(error)
            }
            if(uncertain) throw SavedCoverCloseUncertain(this,failure)
            response=null; finished=true
        } finally { closing=false }
    }
}
internal class SavedCoverCloseUncertain(val owner:SavedCoverIo,cause:Throwable?):
    IOException("Saved-cover I/O close is uncertain; exact participants retained",cause)
