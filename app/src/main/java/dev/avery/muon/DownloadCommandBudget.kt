@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest

internal const val DOWNLOAD_COMMAND_COUNT = 128
internal const val DOWNLOAD_REQUEST_BYTES = 4L * 1024 * 1024

/**
 * Main-thread admission before Media3 queues a command. Keep only counters, not requests or IDs.
 * A reservation lasts until the manager is idle: its application-thread list can lag queued work,
 * so a terminal callback alone cannot reclaim room. Idle reconciliation includes STOPPED requests.
 * Pending commands are capped separately, so removing a resident row can free a full budget.
 * Repeated Adds are charged independently; a rehydrated old request is charged as well
 * as an Add's new payload. This conservatively covers merge/Remove retention without clipping data.
 * Logical payload/count bounds, not a measured heap, Binder delivery or native-cache guarantee.
 */
internal class DownloadCommandBudget(private val capacity: Int = DOWNLOAD_COMMAND_COUNT,
    private val maximum: Long = DOWNLOAD_REQUEST_BYTES) {
    init { require(capacity >= 0 && maximum >= 0) }
    private var seeded = false
    private var count = 0
    private var commands = 0
    private var bytes = 0L

    fun admit(manager: DownloadManager, incoming: DownloadRequest?, previous: DownloadRequest?): Boolean {
        if (!manager.isInitialized) return false
        if (!seeded || manager.isIdle) {
            count = 0
            commands = 0
            bytes = 0
            for (download in manager.currentDownloads) {
                count = (count + 1).coerceAtMost(capacity)
                bytes = (bytes + moveRequestBytes(download.request)).coerceAtMost(maximum)
            }
            seeded = true
        }
        if (commands >= capacity || (incoming != null && !moveCommandFits(incoming))) return false
        val id = incoming?.id ?: previous?.id
        val resident = manager.currentDownloads.any { it.request.id == id }
        val newRow = !resident && (incoming != null || previous != null)
        if (newRow && count >= capacity) return false
        val cost = (incoming?.let(::moveRequestBytes) ?: 0L) +
            (if (!resident) previous?.let(::moveRequestBytes) ?: 0L else 0L)
        if (cost > maximum - bytes) return false
        if (newRow) count++
        commands++
        bytes += cost
        return true
    }
}
