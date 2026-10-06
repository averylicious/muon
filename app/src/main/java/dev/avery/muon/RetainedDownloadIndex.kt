package dev.avery.muon

import androidx.media3.common.util.UnstableApi
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
        if (!inspected) {
            val unfinished = ArrayList<Download>()
            actual.getDownloads(Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
                Download.STATE_REMOVING, Download.STATE_RESTARTING).use { cursor ->
                while (cursor.moveToNext()) unfinished += cursor.download
            }
            // Close the cursor before updating; an IO failure propagates to Media3's initialization,
            // which loads no tasks. Partial state updates still preserve the requests and their bytes.
            for (row in unfinished) {
                val progress = DownloadProgress().apply {
                    bytesDownloaded = row.bytesDownloaded
                    percentDownloaded = row.percentDownloaded
                }
                actual.putDownload(Download(row.request, Download.STATE_STOPPED, row.startTimeMs,
                    row.updateTimeMs, row.contentLength, RETAINED_STOP_REASON, Download.FAILURE_REASON_NONE, progress))
            }
            inspected = true
        }
    }
}
