package dev.avery.muon

import java.io.File
import java.security.MessageDigest
import okhttp3.Request

/**
 * The cover of each downloaded song, kept beside the download (#112) so the offline library shows
 * pictures. The artwork cache cannot be relied on for this: it is trimmed to a size and an age, and
 * Disconnect clears it, while downloads survive both. Removed with the download.
 */
internal class DownloadArt(private val dir: File) {
    private fun file(id: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    fun has(id: String): Boolean = file(id).isFile

    /** The kept cover for the song an artwork address names, if that song was downloaded. */
    fun forArtwork(url: String): ByteArray? {
        val id = downloadIdForArtwork(url) ?: return null
        return runCatching { file(id).takeIf { it.isFile }?.readBytes() }.getOrNull()
    }

    /** Fetches and keeps [id]'s cover. Blocking; call it off the main thread. */
    fun fetch(id: String) {
        if (has(id)) return
        val number = id.substringAfterLast('/')
        val origin = id.substringBeforeLast('/')
        val url = runCatching { ServerEndpoint.parse(origin).url("/api1/pic/medium/$number") }.getOrNull() ?: return
        runCatching {
            Transport.metadataClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null || source.request(4 * 1024 * 1024 + 1L)) return
                val bytes = source.readByteArray()
                if (bytes.isEmpty()) return
                dir.mkdirs()
                val temp = File(dir, file(id).name + ".part")
                temp.writeBytes(bytes)
                temp.renameTo(file(id))
            }
        }
    }

    fun remove(id: String) { file(id).delete() }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }
}

/** The download an artwork address belongs to: "origin/id" for a song's small or medium picture. */
internal fun downloadIdForArtwork(url: String): String? {
    val match = Regex("(https?://[^/]+)/api1/pic/(small|medium)/([0-9]+)").matchEntire(url) ?: return null
    val origin = runCatching { ServerEndpoint.parse(match.groupValues[1]).origin }.getOrNull() ?: return null
    return downloadId(origin, match.groupValues[3].toLong())
}
