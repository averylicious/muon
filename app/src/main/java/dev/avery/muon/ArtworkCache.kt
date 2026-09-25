package dev.avery.muon

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** How much artwork may stay on disk: roughly a large library's list thumbnails plus some covers. */
internal const val ARTWORK_DISK_BYTES = 64L * 1024 * 1024

/**
 * How long a stored picture is trusted. Tauon serves no cache headers, and after a library rebuild it
 * can give a track number to a different song, so nothing is kept for ever; a week spares every
 * restart without letting a wrong cover linger.
 */
internal const val ARTWORK_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Artwork as the server sent it, kept on disk so a restart does not fetch every thumbnail again.
 *
 * Files are named by a hash of the full URL, which includes the server's origin, so two servers never
 * share a picture. When the total passes [maxBytes], the oldest files go first. The running total is
 * counted once and then kept, so a first scroll through a large library does not list the directory
 * for every picture it stores. Every failure is a miss: the picture is fetched as if nothing were kept.
 */
internal class ArtworkDiskCache(private val dir: File, private val maxBytes: Long = ARTWORK_DISK_BYTES,
    private val maxAgeMs: Long = ARTWORK_MAX_AGE_MS, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var total = -1L

    fun read(url: String): ByteArray? = runCatching {
        val file = file(url)
        if (!file.isFile) return null
        if (now() - file.lastModified() > maxAgeMs) {
            synchronized(lock) { if (total >= 0) total -= file.length(); file.delete() }
            return null
        }
        file.readBytes()
    }.getOrNull()

    fun write(url: String, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val target = file(url)
            // A name of its own, so two loads of the same picture never write into one temporary file.
            val temp = File.createTempFile(target.name, ".tmp", dir)
            temp.writeBytes(bytes)
            synchronized(lock) {
                val replaced = if (target.isFile) target.length() else 0L
                if (!temp.renameTo(target)) { temp.delete(); return }
                if (total >= 0) total += bytes.size - replaced
                if (total < 0 || total > maxBytes) trim()
            }
        }
    }

    /** Forgets every stored picture, as when leaving a server. */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            total = 0
        }
    }

    // Called with the lock held. Counts from the directory, then drops the oldest until under the cap.
    // A temporary file an interrupted write left behind is removed once it is clearly abandoned.
    private fun trim() {
        dir.listFiles { f -> f.name.endsWith(".tmp") && now() - f.lastModified() > 60_000 }?.forEach { it.delete() }
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }.orEmpty()
        var sum = files.sumOf { it.length() }
        if (sum > maxBytes) for (file in files.sortedBy { it.lastModified() }) {
            if (sum <= maxBytes) break
            val size = file.length()
            if (file.delete()) sum -= size
        }
        total = sum
    }

    private fun file(url: String) = File(dir, key(url))

    companion object {
        fun key(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** The one disk cache, in the app's cache directory, where Android may clear it under pressure. */
internal object ArtworkStore {
    @Volatile private var disk: ArtworkDiskCache? = null

    fun disk(context: Context): ArtworkDiskCache = disk ?: synchronized(this) {
        disk ?: ArtworkDiskCache(File(context.applicationContext.cacheDir, "artwork")).also { disk = it }
    }
}
