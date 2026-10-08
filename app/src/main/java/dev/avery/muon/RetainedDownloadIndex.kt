package dev.avery.muon

import android.os.Looper
import androidx.media3.common.util.UnstableApi
import java.io.IOException
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
internal class RetainedDownloadIndex(private val actual: WritableDownloadIndex) : WritableDownloadIndex by actual {
    private var inspectionFailure: IOException? = null
    private var inspected = false // Protected by inspect; bootstrap/saved inventory also read this index.

    override fun setDownloadingStatesToQueued() {
        inspect()
        actual.setDownloadingStatesToQueued()
    }

    override fun getDownloads(vararg states: Int): DownloadCursor {
        inspect() // A UI inventory/bootstrap must not observe the pre-preservation states first.
        return actual.getDownloads(*states)
    }

    @Synchronized private fun inspect() {
        inspectionFailure?.let { throw it }
        if (!inspected) {
            // Counts can be requested from Settings before initialization. Do not move SQLite writes
            // onto the UI thread; the caller reports not ready and the manager's worker initializes.
            if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("Saved copies are still initializing")
            try {
                // Only the IDs wait while the cursor is open (#253), not each row's request and stored tags.
                val pending = ArrayList<String>()
                actual.getDownloads(*UNFINISHED).use { cursor ->
                    while (cursor.moveToNext()) pending += cursor.download.request.id
                }
                // Close the cursor before updating; an IO failure propagates to Media3's initialization,
                // which loads no tasks. Partial state updates still preserve the requests and their bytes.
                for (id in pending) {
                    // One full record at a time, read again just before its write. In supported startup no
                    // other writer runs before this finishes: Media3's worker is waiting in this call, and its
                    // commands wait for initialization. A row gone or no longer unfinished means one did, so
                    // nothing is written for it (no row recreated, no newer record overwritten) and the rest
                    // is refused like any failed read.
                    val row = actual.getDownload(id)
                    if (row == null || row.state !in UNFINISHED) throw IOException("A retained download changed during startup")
                    val progress = DownloadProgress().apply {
                        bytesDownloaded = row.bytesDownloaded
                        percentDownloaded = row.percentDownloaded
                    }
                    actual.putDownload(Download(row.request, Download.STATE_STOPPED, row.startTimeMs,
                        row.updateTimeMs, row.contentLength, RETAINED_STOP_REASON, Download.FAILURE_REASON_NONE, progress))
                }
                inspected = true
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
