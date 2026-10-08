package dev.avery.muon

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.WritableDownloadIndex

/**
 * The download index as each shelf's DownloadManager is given it (#253). Media3 1.11.0's
 * DownloadManager.InternalHandler.initialize asks, once, for every QUEUED, STOPPED, DOWNLOADING, REMOVING and
 * RESTARTING row and keeps each as a full Download for the rest of the process. After [RetainedDownloadIndex]
 * has stopped every unfinished row (reason [RETAINED_STOP_REASON]), that load is only stopped rows, which no
 * supported command starts. This view leaves STOPPED out of that one exact query, so the manager holds only
 * what this process adds or removes.
 *
 * Nothing else changes: no row is written, hidden from any other query or deleted, and all-state and single-
 * state reads stay complete. Any query of another shape (another order, duplicates, other states) passes
 * through unchanged, which keeps today's retention rather than omitting more. In the pinned sources an
 * omitted row is still read from the index by addDownload and removeDownload (loadFromIndex = true). The
 * commands that would treat it differently, setStopReason and removeAllDownloads, are refused before Media3
 * by [OfflineStore.admitCommand]; see docs/handoffs/2026-10-08-manager-startup-view.md.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ManagerStartupIndex(private val actual: WritableDownloadIndex) : WritableDownloadIndex by actual {
    override fun getDownloads(vararg states: Int): DownloadCursor =
        actual.getDownloads(*(if (states.contentEquals(STARTUP_QUERY)) STARTUP_WITHOUT_STOPPED else states))

    private companion object {
        /** Exactly as DownloadManager.InternalHandler.initialize passes it (media3-exoplayer 1.11.0). */
        val STARTUP_QUERY = intArrayOf(Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_DOWNLOADING,
            Download.STATE_REMOVING, Download.STATE_RESTARTING)
        val STARTUP_WITHOUT_STOPPED = intArrayOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING,
            Download.STATE_REMOVING, Download.STATE_RESTARTING)
    }
}
