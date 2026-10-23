package dev.avery.muon

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** Fresh fixed-child observations, never an identity cache. The caller still brackets this with
 * canonical root/type/inode checks and its live volume/route/barrier gates. A secure directory
 * handle avoids repeatedly walking the full parent path for each child; bind that handle to the
 * expected root inode before using relative NOFOLLOW views. Unsupported providers keep full-path
 * NOFOLLOW observations. No enumeration, retained native index or background worker.
 *
 * One synchronous handle per shelf. Unknown close retains the actual handle in fixed ownership,
 * refuses all later opens and never retries close. Serializing only this observer avoids sharing
 * the non-thread-safe tracker; it acquires only its own observer monitor.
 */
internal class PartitionJournalObservation(private val directory: File,
    private val directories: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) },
    closeHandle: (Closeable) -> Unit = { it.close() }, private val onMode: ((Boolean) -> Unit)? = null) {
    private val ownership = MigrationIoOwnership(closeHandle = closeHandle)
    private var observing = false
    private var modeReported = false

    @Synchronized fun check(rootIdentity: Any, expected: Array<Any?>) {
        if (observing || !ownership.quiescent) throw IOException("Shelf journal observation is busy or uncertain")
        if (expected.size != PARTITION_BOOTSTRAP_JOURNALS.size || expected.any { it == null })
            throw IOException("Shelf journal identities are incomplete")
        observing = true
        try {
            ownership.useOwned({ directories(directory.toPath()) }) { stream ->
                val secure = stream as? SecureDirectoryStream<Path>
                if (!modeReported) { modeReported = true; runCatching { onMode?.invoke(secure != null) } }
                if (secure != null) {
                    val root = secure.getFileAttributeView(BasicFileAttributeView::class.java)?.readAttributes()
                        ?: throw IOException("Shelf directory handle identity unavailable")
                    if (!root.isDirectory || root.fileKey() != rootIdentity)
                        throw IOException("Shelf directory handle belongs to another root")
                }
                for (i in PARTITION_BOOTSTRAP_JOURNALS.indices) {
                    val name = PARTITION_BOOTSTRAP_JOURNALS[i]
                    val attributes = if (secure != null) {
                        val child = directory.toPath().fileSystem.getPath(name)
                        secure.getFileAttributeView(child, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                            ?.readAttributes() ?: throw IOException("Shelf journal attributes unavailable")
                    } else Files.readAttributes(File(directory, name).toPath(), BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS)
                    if (!attributes.isRegularFile || attributes.fileKey() != expected[i])
                        throw IOException("Partition shelf journal replaced")
                }
            }
        } finally { observing = false }
    }
}
