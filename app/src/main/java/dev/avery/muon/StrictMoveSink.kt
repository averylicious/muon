package dev.avery.muon

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One open destination file of a move copy: the steps [StrictMoveSink] must see succeed, one by one. */
internal interface MoveFileOutput : Closeable {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    /** Hands buffered bytes to the file. */
    fun flush()
    /** Asks the file system to put the file's bytes on storage (FileDescriptor.sync). */
    fun sync()
}

/** Opens a [MoveFileOutput] for a span file the cache has reserved. */
internal fun interface MoveFileOutputs {
    fun open(file: File): MoveFileOutput

    companion object {
        /** A file stream behind one bounded buffer; every step's failure reaches the caller. */
        val Real = MoveFileOutputs { file ->
            val stream = FileOutputStream(file)
            val buffered = try { BufferedOutputStream(stream, 64 * 1024) } catch (e: Throwable) { stream.close(); throw e }
            object : MoveFileOutput {
                override fun write(buffer: ByteArray, offset: Int, length: Int) = buffered.write(buffer, offset, length)
                override fun flush() = buffered.flush()
                override fun sync() = stream.fd.sync()
                // Closing the buffer flushes (already done) and closes the stream; a failure is thrown, not hidden.
                override fun close() = buffered.close()
            }
        }
    }
}

/**
 * The cache write sink for a move's copy only (#230), in place of Media3's CacheDataSink. CacheDataSink
 * closes its file quietly before committing it (pinned media3-datasource 1.11.0, `closeCurrentOutputStream`:
 * flush, `Util.closeQuietly`, then `commitFile`), and CacheWriter closes quietly after a failure, so neither
 * can show that a destination file was written out. This sink commits a file only after its own flush,
 * descriptor sync and close each returned normally, and keeps the first failure it saw in [failure] even
 * when its caller closes it quietly.
 *
 * One file is open at a time, written through one bounded buffer; each open is one span file, unfragmented.
 * A file that failed before being committed is deleted: it was reserved by this sink and never entered the
 * cache. A file whose commit itself failed is left alone, since the cache may already partly know it.
 * Nothing else is ever deleted. A sync asks for storage, as the platform allows; it is not a transaction,
 * crash recovery or proof the bytes will survive a removed card or a crash (#179).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class StrictMoveSink(private val cache: Cache, private val outputs: MoveFileOutputs = MoveFileOutputs.Real) : DataSink {
    private var file: File? = null
    private var output: MoveFileOutput? = null
    private var written = 0L

    /** The first failure this sink saw, kept even when its caller closed it quietly. */
    @Volatile var failure: IOException? = null
        private set

    /** Files this sink reserved, and files it committed after every step succeeded. */
    @Volatile var opened = 0
        private set
    @Volatile var committed = 0
        private set

    /** Evidence for a move: every file it opened was flushed, synced, closed and committed, and none is open. */
    val clean: Boolean get() = failure == null && output == null && committed == opened

    override fun open(dataSpec: DataSpec) {
        val key = dataSpec.key ?: throw record(IOException("A move copy needs a cache key"))
        if (output != null) throw record(IOException("The previous move file is still open"))
        opened++
        try {
            val reserved = cache.startFile(key, dataSpec.position, dataSpec.length)
            // startFile returns a pathname, not an exclusive reservation. A same-name unindexed file
            // may belong to an earlier uncertain commit: neither truncate nor delete it.
            if (!reserved.createNewFile()) throw IOException("The move destination file already exists")
            file = reserved
            output = outputs.open(reserved)
            written = 0
        } catch (e: IOException) {
            file?.delete() // Reserved by this sink and never committed: no other bytes.
            file = null
            throw record(e)
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        val out = output ?: throw record(IOException("No move file is open"))
        try { out.write(buffer, offset, length) } catch (e: IOException) { throw record(e) }
        written += length
    }

    override fun close() {
        val out = output ?: return
        val reserved = checkNotNull(file)
        output = null
        file = null
        var problem: IOException? = null
        try { out.flush(); out.sync() } catch (e: IOException) { problem = e }
        try { out.close() } catch (e: IOException) {
            val first = problem
            if (first == null) problem = e else first.addSuppressed(e)
        }
        // A file is committed only if this one, and everything before it, went through cleanly.
        val earlier = failure
        if (problem == null && earlier == null) {
            try {
                cache.commitFile(reserved, written)
                committed++
                return
            } catch (e: IOException) {
                // Not deleted: the cache may have half-recorded it. Kept like any other uncertain bytes.
                throw record(e)
            }
        }
        reserved.delete() // Never committed: this sink's own reserved file, not anyone else's bytes.
        throw problem?.let(::record) ?: IOException("An earlier move write failed", earlier)
    }

    private fun record(e: IOException): IOException {
        if (failure == null) failure = e
        return e
    }
}
