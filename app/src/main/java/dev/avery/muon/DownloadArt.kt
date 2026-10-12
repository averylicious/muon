package dev.avery.muon

import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import okio.source
import okio.buffer
import java.io.OutputStream
import okhttp3.Call
import java.security.MessageDigest
import okhttp3.Request

/**
 * The covers of saved copies, kept beside them (#112) so they show without Tauon. The artwork cache
 * cannot be relied on for this: it is trimmed to a size and an age, and Disconnect clears it.
 *
 * Each new save owns its cover (#213), named from that entry's own request ID, fetched once when the
 * save is asked for and removed with it. Older downloads kept one cover per track number, shared by
 * every copy of that number and possibly fetched after the number had moved to another song; those
 * files are left as they are, neither shown for a saved copy, served for a live song, adopted by a new
 * save nor deleted.
 */
internal class DownloadArt(private val dir: File,
    private val calls:Call.Factory?=null,
    private val outputs:(File)->OutputStream={FileOutputStream(it)},
    private val barrier:SavedStorageBarrier?=null,
    private val inputs:(File)->InputStream={FileInputStream(it)}) {
    @Volatile private var retained:SavedCoverIo?=null
    private fun retain(owner:SavedCoverIo)=synchronized(this) {
        if(retained==null) retained=owner
        barrier?.invalidate()
    }
    private fun entryFile(requestId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("muon-saved-entry\u0000$requestId".toByteArray())
        return File(dir, "entry-" + digest.joinToString("") { "%02x".format(it) })
    }

    /** Whether the entry with this request ID has its own cover. */
    fun hasEntry(requestId: String): Boolean = retained==null && entryFile(requestId).isFile

    /** The entry's own cover, if it has one and is within the cover size cap; never an older per-track cover. */
    fun forEntry(requestId: String): ByteArray? {
        if(retained!=null) return null
        val permit=try { barrier?.shared() } catch(_:IOException) { return null }
        val io=SavedCoverIo(); var unknown=false
        try {
            try {
                permit?.check()
                val file=entryFile(requestId)
                if(!file.isFile || file.length() !in 1..COVER_BYTES) return null
                val input=inputs(file).also { io.input=it }
                val source=input.source().buffer().also { io.source=it }
                if(source.request(COVER_BYTES+1)) return null
                val bytes=source.readByteArray(source.buffer.size)
                permit?.check()
                return bytes.takeIf { it.isNotEmpty() }
            } finally { io.finish() }
        } catch(failure:SavedCoverCloseUncertain) {
            unknown=true; permit?.quarantine(failure.owner); retain(failure.owner); return null
        } catch(_:Exception) { return null }
        finally { if(!unknown) permit?.close() }
    }

    /**
     * Fetches and keeps the entry's own cover from [url]. Blocking; call it off the main thread. Uses the
     * finite metadata client, whose whole-call deadline also bounds a slow-drip response, and the cap.
     */
    fun fetchEntry(requestId: String, url: String) {
        retained?.let { throw SavedCoverCloseUncertain(it,null) }
        if (hasEntry(requestId)) return
        try {
            val io=SavedCoverIo()
            val target=entryFile(requestId)
            val temp=File(dir,target.name+".part")
            var failure:Throwable?=null
            try {
                io.response=(calls ?: Transport.metadataClient).newCall(Request.Builder().url(url).build()).execute()
                val response=requireNotNull(io.response)
                val source=response.body?.source()?.also { io.source=it }
                if(!response.isSuccessful || source==null || source.request(COVER_BYTES+1)) return
                val bytes=source.readByteArray(source.buffer.size)
                if(bytes.isEmpty()) return
                dir.mkdirs()
                val output=outputs(temp).also { io.output=it }
                output.write(bytes); output.flush()
            } catch(error:Throwable) { failure=error; throw error }
            finally { try { io.finish() } catch(unknown:SavedCoverCloseUncertain) {
                if(failure!=null && failure!==unknown && failure!==unknown.cause) unknown.addSuppressed(failure)
                throw unknown
            } }
            // Publication is after BOTH observable closes, never after a quiet response close.
            temp.renameTo(target)
        } catch(unknown:SavedCoverCloseUncertain) { retain(unknown.owner); throw unknown }
        catch(_:Exception) { /* Optional cover failure; known-closed audio/index remain unaffected. */ }
    }

    /** Removes the entry's own cover; any other file, including an older per-track cover, stays. */
    fun removeEntry(requestId: String) {
        if(retained!=null) throw IOException("Saved-cover ownership uncertain; removal refused")
        entryFile(requestId).delete()
    }

    /** Confirm only this entry's own cover absence under the actual exclusive saved-storage gate.
     * Caller cancels queued fetches first. Older shared per-track covers are never named here. */
    internal fun removeEntryConfirmed(requestId:String,permit:SavedStorageBarrier.Lease) {
        val gate=barrier ?: throw IOException("Confirmed cover removal needs its actual storage gate")
        if(!permit.exclusive || !permit.belongsTo(gate) || retained!=null)
            throw IOException("Confirmed cover removal ownership unavailable")
        permit.check()
        val file=entryFile(requestId)
        if(dir.absoluteFile!=dir.canonicalFile || file.absoluteFile!=file.canonicalFile ||
            java.nio.file.Files.isSymbolicLink(dir.toPath()) || java.nio.file.Files.isSymbolicLink(file.toPath()) ||
            (dir.exists() && !dir.isDirectory)) throw IOException("Saved cover path changed")
        java.nio.file.Files.deleteIfExists(file.toPath())
        if(java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw IOException("Saved cover removal unconfirmed")
        permit.check()
    }
    private companion object { const val COVER_BYTES = 4L * 1024 * 1024 }
}
