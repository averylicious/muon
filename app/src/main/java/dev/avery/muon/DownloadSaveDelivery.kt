@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.offline.DownloadRequest
import java.util.UUID

internal const val SAVE_DELIVERY_TOKEN = "dev.avery.muon.save-delivery-token"
internal const val SAVE_DELIVERY_TIMEOUT_MS = 60_000L

internal data class SaveDeliveryResult(val requested: Int, val accepted: Int,
    val missingCovers: Int, val unconfirmed: Int) {
    val refused: Int get() = requested - accepted - unconfirmed
}

/**
 * One process-owned, bounded batch of new saves awaiting the real service's acknowledgement. Exact
 * shelf/request checks make a token single-use; a process restart or timeout grants no authority to
 * a delayed intent. Acknowledgement means Media3 accepted the command, not downloaded/persisted audio.
 * The handler has only one deadline runnable, removed when the batch finishes. Logical held payload
 * includes cover text; Android's already-submitted Binder/start queue is not an exact heap bound.
 */
internal class DownloadSaveDelivery(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val timer: Handler = Handler(Looper.getMainLooper()),
    private val capacity: Int = DOWNLOAD_COMMAND_COUNT,
    private val maximum: Long = DOWNLOAD_REQUEST_BYTES,
    private val timeout: Long = SAVE_DELIVERY_TIMEOUT_MS,
) {
    init { require(capacity >= 0 && maximum >= 0 && timeout > 0) }
    private class Item(val request: DownloadRequest, val cover: String, var claimed: Boolean = false)
    private class Batch(val shelf: Shelf, val started: Long, val items: LinkedHashMap<String, Item>,
        val cover: (String, String) -> Boolean, val finished: (SaveDeliveryResult) -> Unit) {
        val total = items.size
        var accepted = 0
        var missingCovers = 0
        var unconfirmed = 0
        lateinit var deadline: Runnable
    }
    private var batch: Batch? = null

    /** Entire prepared batch or refusal; no prefix, clipping, second pending batch or network here. */
    @Synchronized fun begin(shelf: Shelf, requests: List<Pair<DownloadRequest, String>>,
        cover: (String, String) -> Boolean, finished: (SaveDeliveryResult) -> Unit): List<String>? {
        if (batch != null || requests.isEmpty() || requests.size > capacity) return null
        var bytes = 0L
        for ((request, address) in requests) {
            val cost = moveRequestBytes(request) + 2L * address.length + 256L
            if (!moveCommandFits(request) || cost > maximum - bytes) return null
            bytes += cost
        }
        val items = LinkedHashMap<String, Item>()
        for ((request, address) in requests) items[UUID.randomUUID().toString()] = Item(request, address)
        val pending = Batch(shelf, clock(), items, cover, finished)
        pending.deadline = Runnable { expire(pending) }
        batch = pending
        if (!runCatching { timer.postDelayed(pending.deadline, timeout) }.getOrDefault(false)) {
            batch = null
            items.clear()
            return null
        }
        return items.keys.toList()
    }

    /** Producer checks before sending, so a retired batch does not submit its remaining commands. */
    @Synchronized fun pending(token: String): Boolean {
        expireIfDue()
        return batch?.items?.get(token)?.claimed == false
    }

    /** Before any manager/ownership mutation; receipt belongs to this exact shelf and full request. */
    @Synchronized fun claim(token: String, shelf: Shelf?, request: DownloadRequest?): Boolean {
        expireIfDue()
        val current = batch ?: return false
        val item = current.items[token] ?: return false
        if (item.claimed || current.shelf !== shelf || item.request != request) return false
        item.claimed = true
        return true
    }

    /** Called after the service returned, or on a send/admission failure. Unknown/retired tokens do nothing. */
    @Synchronized fun complete(token: String, accepted: Boolean, unconfirmed: Boolean = false) {
        val current = batch ?: return
        val item = current.items.remove(token) ?: return
        if (accepted && item.claimed) {
            current.accepted++
            if (!runCatching { current.cover(item.request.id, item.cover) }.getOrDefault(false))
                current.missingCovers++
        }
        if (unconfirmed) current.unconfirmed++
        if (current.items.isEmpty()) finish(current, 0)
    }

    private fun expireIfDue() {
        val current = batch ?: return
        if (clock() - current.started >= timeout) finish(current, current.items.size)
    }

    @Synchronized private fun expire(expected: Batch) {
        if (batch === expected) finish(expected, expected.items.size)
    }

    private fun finish(current: Batch, unconfirmed: Int) {
        batch = null
        timer.removeCallbacks(current.deadline)
        current.items.clear()
        // Clear the producer's slot before notifying, even if a notification callback fails.
        runCatching { current.finished(SaveDeliveryResult(current.total, current.accepted,
            current.missingCovers, unconfirmed + current.unconfirmed)) }
    }
}

internal fun saveDeliveryMessage(result: SaveDeliveryResult): String {
    val queued = when {
        result.accepted == 0 -> "No new saved copies were confirmed as queued."
        result.accepted == 1 -> "Queued one saved copy. It's under Saved copies."
        else -> "Queued ${result.accepted} saved copies. They're under Saved copies."
    }
    val refused = if (result.refused == 0) "" else
        " ${result.refused} ${if (result.refused == 1) "copy couldn't" else "copies couldn't"} be queued. " +
            "Retry when current work finishes, or select fewer songs."
    val late = if (result.unconfirmed == 0) "" else
        " ${result.unconfirmed} save ${if (result.unconfirmed == 1) "request couldn't" else "requests couldn't"} be confirmed. " +
            "Check Saved copies before retrying missing songs."
    val covers = if (result.missingCovers == 0) "" else
        " Some covers weren't queued because artwork is busy; the audio requests were accepted."
    return queued + refused + late + covers
}
