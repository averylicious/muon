@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.offline.DownloadManager
import java.util.UUID

internal const val REMOVAL_DELIVERY_TOKEN = "dev.avery.muon.removal-delivery-token"
internal const val REMOVAL_DELIVERY_TIMEOUT_MS = 60_000L
/** Sends per checked removal before it is reported as unchanged: refusals are retried only while others progress. */
private const val REMOVAL_ATTEMPTS = 3

/**
 * How one removal batch ended. [accepted]: the real service passed the removal to Media3, which queued it
 * (not yet deleted). [unconfirmed]: sent, but no confirmation; Media3 may have queued it. Everything else
 * was refused before Media3 or never sent, and is unchanged.
 */
internal data class RemovalDeliveryResult(val requested: Int, val accepted: Int, val unconfirmed: Int) {
    val unchanged: Int get() = requested - accepted - unconfirmed
}

/** One checked removal: a row on [shelf] whose sole ownership of its bytes was verified before selection. */
internal class RemovalTarget(val shelf: Shelf, val id: String)

/** Whole-selection bound for one batch: request count and logical ID text. */
internal class RemovalSelection(private val capacity: Int = DOWNLOAD_COMMAND_COUNT,
    private val maximum: Long = DOWNLOAD_REQUEST_BYTES) {
    val targets = ArrayList<RemovalTarget>()
    private var bytes = 0L

    /** Adds [id] if it fits; otherwise nothing changes and the caller counts it as left for later. */
    fun take(shelf: Shelf, id: String): Boolean {
        val cost = removalCost(id)
        if (targets.size >= capacity || cost > maximum - bytes) return false
        targets += RemovalTarget(shelf, id)
        bytes += cost
        return true
    }
}

internal fun removalCost(id: String): Long = 2L * id.length + 128L

/**
 * One process-owned, bounded batch of checked removals awaiting the real service's acknowledgement (#253).
 * The services' command budget admits only [DOWNLOAD_REMOVAL_COUNT] removals per manager until it is idle,
 * so the batch sends at most that many at once and sends the next window only after the manager that ran
 * the last one reports idle. Each send carries a fresh single-use token bound to its exact shelf and ID.
 * A timeout, process restart or replay grants nothing: a retired token's command becomes INIT. A window in
 * which nothing was accepted ends the batch, so a lasting refusal (a move, a wrong card binding) does not
 * spin. Nothing is deleted or rewritten here; this only orders commands and reports what each came to.
 */
internal class DownloadRemovalDelivery(
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val capacity: Int = DOWNLOAD_COMMAND_COUNT,
    private val maximum: Long = DOWNLOAD_REQUEST_BYTES,
    private val window: Int = DOWNLOAD_REMOVAL_COUNT,
    private val timeout: Long = REMOVAL_DELIVERY_TIMEOUT_MS,
) {
    init { require(capacity >= 0 && maximum >= 0 && window > 0 && timeout > 0) }
    private class Item(val target: RemovalTarget) {
        var attempts = 0
        var claimed = false
    }
    private inner class Batch(val total: Int, val send: (RemovalTarget, String) -> Boolean,
        val finished: (RemovalDeliveryResult) -> Unit) {
        val waiting = ArrayDeque<Item>()
        val inFlight = LinkedHashMap<String, Item>()
        var accepted = 0
        var unconfirmed = 0
        var windowShelf: Shelf? = null
        var windowSent = 0
        var windowAccepted = 0
        var windowStarted = 0L
        val deadline = Runnable { expire(this) }
        var idle: DownloadManager.Listener? = null
    }
    private var batch: Batch? = null

    val busy: Boolean @Synchronized get() = batch != null

    /**
     * The entire checked selection, or refusal (another batch pending, or over its bounds). Sends the first
     * window on the calling thread; later windows go from the main thread. No prefix is taken here.
     */
    @Synchronized fun begin(targets: List<RemovalTarget>, send: (RemovalTarget, String) -> Boolean,
        finished: (RemovalDeliveryResult) -> Unit): Boolean {
        if (batch != null || targets.isEmpty() || targets.size > capacity) return false
        var bytes = 0L
        for (target in targets) {
            val cost = removalCost(target.id)
            if (cost > maximum - bytes) return false
            bytes += cost
        }
        val pending = Batch(targets.size, send, finished)
        targets.forEach { pending.waiting.addLast(Item(it)) }
        batch = pending
        sendWindow(pending)
        return true
    }

    /** Before any manager or ownership change: the token's exact shelf and ID, once. */
    @Synchronized fun claim(token: String, shelf: Shelf?, id: String?): Boolean {
        val current = batch ?: return false
        if (clock() - current.windowStarted >= timeout) { finish(current); return false }
        val item = current.inFlight[token] ?: return false
        if (item.claimed || item.target.shelf !== shelf || item.target.id != id) return false
        item.claimed = true
        return true
    }

    /** After the service returned, or on refusal before Media3. Unknown/retired tokens change nothing. */
    @Synchronized fun complete(token: String, accepted: Boolean, unconfirmed: Boolean = false) {
        val current = batch ?: return
        val item = current.inFlight.remove(token) ?: return
        when {
            accepted && item.claimed -> { current.accepted++; current.windowAccepted++ }
            unconfirmed -> current.unconfirmed++
            // Refused before Media3: unchanged. Retried in a later window only while others make progress.
            item.attempts < REMOVAL_ATTEMPTS -> current.waiting.addLast(item)
        }
        if (current.inFlight.isEmpty()) windowDone(current)
    }

    /** A service bound elsewhere refused this exact, still unclaimed command before Media3. */
    @Synchronized fun refused(token: String, shelf: Shelf?) {
        val item = batch?.inFlight?.get(token) ?: return
        if (!item.claimed && item.target.shelf === shelf) complete(token, false)
    }

    private fun sendWindow(current: Batch) {
        if (batch !== current) return
        if (current.waiting.isEmpty()) { finish(current); return }
        val shelf = current.waiting.first().target.shelf
        val chosen = ArrayList<Item>(window)
        val rest = ArrayDeque<Item>()
        while (current.waiting.isNotEmpty()) {
            val item = current.waiting.removeFirst()
            if (chosen.size < window && item.target.shelf === shelf) chosen += item else rest.addLast(item)
        }
        current.waiting.addAll(rest)
        current.windowShelf = shelf
        current.windowSent = 0
        current.windowAccepted = 0
        current.windowStarted = clock()
        main.removeCallbacks(current.deadline)
        if (!runCatching { main.postDelayed(current.deadline, timeout) }.getOrDefault(false)) {
            // No deadline means no bounded wait: send nothing more and report the rest unchanged.
            current.waiting.addAll(0, chosen)
            finish(current)
            return
        }
        // An unavailable card is not sent to (#179 S1); its rows are unchanged.
        val available = shelf.available()
        val tokens = chosen.filter { available }.map { item ->
            // A fresh token per send: an earlier send's token for the same row stays retired.
            UUID.randomUUID().toString().also { current.inFlight[it] = item; item.attempts++; item.claimed = false }
        }
        current.windowSent = tokens.size
        for (token in tokens) {
            val item = current.inFlight[token] ?: continue
            if (!runCatching { current.send(item.target, token) }.getOrDefault(false)) {
                // Not handed to Android: unchanged, and not retried.
                item.attempts = REMOVAL_ATTEMPTS
                complete(token, false)
            }
            if (batch !== current) return
        }
        if (tokens.isEmpty()) windowDone(current)
    }

    private fun windowDone(current: Batch) {
        if (batch !== current) return
        // Nothing accepted although something was sent: a lasting refusal or an uncertain service. Stop.
        if (current.windowSent > 0 && current.windowAccepted == 0) { finish(current); return }
        if (current.waiting.isEmpty()) { finish(current); return }
        val manager = current.windowShelf?.manager
        // The next window waits until the manager that ran this one is idle, when its removal budget resets.
        main.post {
            synchronized(this) {
                if (batch !== current) return@synchronized
                if (manager == null || manager.isIdle) { sendWindow(current); return@synchronized }
                val listener = object : DownloadManager.Listener {
                    override fun onIdle(downloadManager: DownloadManager) {
                        downloadManager.removeListener(this)
                        synchronized(this@DownloadRemovalDelivery) {
                            if (current.idle === this) current.idle = null
                            sendWindow(current)
                        }
                    }
                }
                current.idle = listener
                manager.addListener(listener)
            }
        }
    }

    @Synchronized private fun expire(expected: Batch) {
        if (batch === expected) finish(expected)
    }

    private fun finish(current: Batch) {
        if (batch !== current) return
        batch = null
        main.removeCallbacks(current.deadline)
        current.idle?.let { listener -> current.windowShelf?.manager?.let { m -> main.post { m.removeListener(listener) } } }
        // Sent without a confirmation: Media3 may have queued it. Never reported as unchanged.
        val unconfirmed = current.unconfirmed + current.inFlight.size
        current.inFlight.clear()
        current.waiting.clear()
        // Clear the slot before notifying, even if the notification fails.
        runCatching { current.finished(RemovalDeliveryResult(current.total, current.accepted, unconfirmed)) }
    }
}

/** What a removal batch came to, for one copy ([single]) or Remove all, plus rows left for a later pass. */
internal fun removalDeliveryMessage(result: RemovalDeliveryResult, single: Boolean, later: Int = 0): String? {
    if (single) return when {
        result.accepted == 1 -> null
        result.unconfirmed == 1 -> "Muon couldn't confirm that copy's removal. Check Saved copies before trying again."
        else -> "That copy wasn't removed and is unchanged: saved-copy work is busy. Try again when it finishes."
    }
    val queued = when (result.accepted) {
        0 -> "No saved copies were confirmed as being removed."
        1 -> "Removing 1 saved copy."
        else -> "Removing ${result.accepted} saved copies."
    }
    val unchanged = if (result.unchanged == 0) "" else
        " ${result.unchanged} ${if (result.unchanged == 1) "copy wasn't" else "copies weren't"} removed and " +
            "${if (result.unchanged == 1) "is" else "are"} unchanged. Remove all again when current work finishes."
    val uncertain = if (result.unconfirmed == 0) "" else
        " ${result.unconfirmed} ${if (result.unconfirmed == 1) "removal" else "removals"} couldn't be confirmed. " +
            "Check Saved copies before retrying."
    val rest = if (later == 0) "" else
        " $later more ${if (later == 1) "copy wasn't" else "copies weren't"} included in this pass. " +
            "Remove all again when it finishes."
    return queued + unchanged + uncertain + rest
}
