package dev.avery.muon

import java.io.File
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
internal class DownloadArt(private val dir: File) {
    private fun entryFile(requestId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest("muon-saved-entry\u0000$requestId".toByteArray())
        return File(dir, "entry-" + digest.joinToString("") { "%02x".format(it) })
    }

    /** Whether the entry with this request ID has its own cover. */
    fun hasEntry(requestId: String): Boolean = entryFile(requestId).isFile

    /** The entry's own cover, if it has one and is within the cover size cap; never an older per-track cover. */
    fun forEntry(requestId: String): ByteArray? = runCatching {
        entryFile(requestId).takeIf { it.isFile && it.length() in 1..COVER_BYTES }?.readBytes()
    }.getOrNull()

    /**
     * Fetches and keeps the entry's own cover from [url]. Blocking; call it off the main thread. Uses the
     * finite metadata client, whose whole-call deadline also bounds a slow-drip response, and the cap.
     */
    fun fetchEntry(requestId: String, url: String) {
        if (hasEntry(requestId)) return
        runCatching {
            Transport.metadataClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null || source.request(COVER_BYTES + 1)) return
                val bytes = source.readByteArray()
                if (bytes.isEmpty()) return
                dir.mkdirs()
                val target = entryFile(requestId)
                val temp = File(dir, target.name + ".part")
                temp.writeBytes(bytes)
                temp.renameTo(target)
            }
        }
    }

    /** Removes the entry's own cover; any other file, including an older per-track cover, stays. */
    fun removeEntry(requestId: String) { entryFile(requestId).delete() }

    private companion object { const val COVER_BYTES = 4L * 1024 * 1024 }
}
