package dev.avery.muon

import android.os.Looper
import android.database.sqlite.SQLiteException
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.WritableDownloadIndex

/** An unfinished operation kept after restart; its bytes are not verified against the live server. */
internal const val RETAINED_STOP_REASON = 213

/**
 * Stops retained unfinished operations before DownloadManager's initial task snapshot (#213).
 * Media3 calls setDownloadingStatesToQueued on its internal worker before loading that snapshot.
 * A removing/restarting row would otherwise delete its key even while downloads are paused; queued
 * partial bytes could be extended with audio now served under an old address. Keep every request,
 * progress record and cached byte, changing only the unfinished operation's state/stop reason.
 *
 * This is a supported index decorator, not an asynchronous listener or a throwing downloader:
 * Media3 removes the index record even when Downloader.remove throws. Subsequent operations in this
 * process delegate normally. New saves use fresh names; stopped retained entries are never resumed
 * by Muon's service startup. This does not synchronize card loss or drain pre-existing workers.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class RetainedDownloadIndex(private val actual: WritableDownloadIndex, private val startupFile: File) : WritableDownloadIndex by actual {
    private var inspectionFailure: IOException? = null
    private var inspected = false // Protected by inspect; bootstrap/saved inventory also read this index.

    override fun setDownloadingStatesToQueued() {
        inspect()
        actual.setDownloadingStatesToQueued()
    }

    override fun getDownloads(vararg states: Int): DownloadCursor {
        inspect() // A UI inventory/bootstrap must not observe the pre-preservation states first.
        // DefaultDownloadIndex wraps single-row reads, but its lazy cursor does not wrap SQLite
        // window/row failures. Media3 initialization catches IOException, not SQLiteException.
        val cursor = readIndex { actual.getDownloads(*states) }
        return object : DownloadCursor by cursor {
            override fun getDownload(): Download = readIndex { cursor.download }
            override fun getCount(): Int = readIndex { cursor.count }
            override fun getPosition(): Int = readIndex { cursor.position }
            override fun moveToPosition(position: Int): Boolean = readIndex { cursor.moveToPosition(position) }
            override fun close() = readIndex { cursor.close() }
        }
    }

    private inline fun <T> readIndex(read: () -> T): T = try { read() }
        catch (failure: SQLiteException) { throw IOException("Saved-copy database read failed", failure) }

    @Synchronized private fun inspect() {
        inspectionFailure?.let { throw it }
        if (!inspected) {
            // Counts can be requested from Settings before initialization. Do not move SQLite writes
            // onto the UI thread; the caller reports not ready and the manager's worker initializes.
            if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Saved copies are still initializing")
            try {
                try {
                    // A private, per-shelf scratch file replaces the whole pending-ID list (#253).
                    // Length + exact UTF-16 code units: no writeUTF limit or lossy tag/ID encoding.
                    // Truncate a previous interrupted startup's file; it is never recovery authority.
                    var pending = 0L
                    DataOutputStream(startupFile.outputStream().buffered()).use { output ->
                        actual.getDownloads(*UNFINISHED).use { cursor ->
                            while (cursor.moveToNext()) {
                                val id = cursor.download.request.id
                                output.writeInt(id.length)
                                for (char in id) output.writeChar(char.code)
                                pending++
                            }
                        }
                    }
                    // Close both scan/output before any update. Re-read one full row at a time just
                    // before its write; missing/changed rows refuse without recreating or overwriting.
                    DataInputStream(startupFile.inputStream().buffered()).use { input ->
                        while (pending > 0) {
                            val length = input.readInt()
                            if (length < 0) throw IOException("Invalid retained startup ID length")
                            val text = StringBuilder(minOf(length, 4096))
                            repeat(length) { text.append(input.readChar()) }
                            val row = actual.getDownload(text.toString())
                            if (row == null || row.state !in UNFINISHED)
                                throw IOException("A retained download changed during startup")
                            val progress = DownloadProgress().apply {
                                bytesDownloaded = row.bytesDownloaded
                                percentDownloaded = row.percentDownloaded
                            }
                            actual.putDownload(Download(row.request, Download.STATE_STOPPED, row.startTimeMs,
                                row.updateTimeMs, row.contentLength, RETAINED_STOP_REASON, Download.FAILURE_REASON_NONE, progress))
                            pending--
                        }
                        if (input.read() != -1) throw IOException("Unexpected retained startup IDs")
                    }
                } finally {
                    // No scratch IDs are kept intentionally; crash leftovers are overwritten next start.
                    startupFile.delete()
                }
                inspected = true
            } catch (failure: SQLiteException) {
                val refused = IOException("Saved-copy database read failed", failure)
                inspectionFailure = refused
                throw refused
            } catch (failure: IOException) {
                // Once initialization fails, a later rescan could include current-process commands.
                // Keep this instance closed; a new process retries before its manager starts tasks.
                inspectionFailure = failure
                throw failure
            }
        }
    }

    private companion object {
        /** The states of an operation a manager would start or continue: what startup stops. */
        val UNFINISHED = intArrayOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
            Download.STATE_REMOVING, Download.STATE_RESTARTING)
    }
}
