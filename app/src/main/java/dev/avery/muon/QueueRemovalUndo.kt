@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

internal val REMOVE_QUEUE_ITEM = SessionCommand("dev.avery.muon.REMOVE_QUEUE_ITEM", Bundle.EMPTY)
internal val RESTORE_QUEUE_REMOVAL = SessionCommand("dev.avery.muon.RESTORE_QUEUE_REMOVAL", Bundle.EMPTY)

/** Complete shuffled order, including entries before the current song; bounded by the queue budget. */
internal fun queueShuffledOrder(player: Player): List<Int> {
    val timeline = player.currentTimeline
    val count = player.mediaItemCount
    if (timeline.windowCount != count || count > SavedQueueLimits().items) return emptyList()
    val order = ArrayList<Int>(count)
    val seen = HashSet<Int>()
    var index = timeline.getFirstWindowIndex(true)
    while (index in 0 until count && seen.add(index)) {
        order += index
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
    }
    return if (order.size == count && index == C.INDEX_UNSET) order else emptyList()
}

/** Fixed-size identity of an ordered (song, occurrence) list; lengths delimit every field. */
internal fun queueSnapshotDigest(occurrences: List<Pair<String, String>>): ByteArray {
    val hash = MessageDigest.getInstance("SHA-256")
    fun length(n: Int) { for (shift in 24 downTo 0 step 8) hash.update((n ushr shift).toByte()) }
    length(occurrences.size)
    for ((id, key) in occurrences) for (text in arrayOf(id, key)) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        length(bytes.size); hash.update(bytes)
    }
    return hash.digest()
}

/**
 * One removal's Undo, used and closed on the player's application thread. The service removes the row
 * and captures what Undo needs from its actual player in the same operation: a controller's own
 * timeline is unshuffled while its earlier edits are pending, so it cannot supply the playing order.
 */
internal class QueueRemovalUndo private constructor(private val player: Player,
    private val ticket: QueueRemovalTicket?) : AutoCloseable {
    private var closed = false
    private var attempted = false
    /** False when the row was removed but its ownership couldn't be proved (legacy/cloned keys). */
    val restorable: Boolean get() = ticket != null

    /** The service acknowledges both insertion and shuffled placement, not just the local mask. */
    fun undo(): ListenableFuture<Boolean> {
        if (closed || attempted) return Futures.immediateFuture(false)
        attempted = true
        val ticket = ticket ?: return Futures.immediateFuture(false)
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return Futures.immediateFuture(false)
        if (player is MediaController) {
            if (!player.availableSessionCommands.contains(RESTORE_QUEUE_REMOVAL)) return Futures.immediateFuture(false)
            return Futures.transform(player.sendCustomCommand(RESTORE_QUEUE_REMOVAL, ticket.toBundle()),
                { it?.resultCode == SessionResult.RESULT_SUCCESS }, MoreExecutors.directExecutor())
        }
        val exo = player as? ExoPlayer ?: return Futures.immediateFuture(false)
        return Futures.immediateFuture(ticket.apply(player, exo, ticket.item))
    }

    suspend fun undoAwait(): Boolean = suspendCancellableCoroutine { continuation ->
        val future = undo()
        future.addListener({
            continuation.resume(runCatching { future.get() }.getOrDefault(false))
        }, MoreExecutors.directExecutor())
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    override fun close() { closed = true }

    companion object {
        /** Null: refused, and nothing was removed. Otherwise the row is gone, Undo or not. */
        fun remove(player: Player, index: Int, item: MediaItem): ListenableFuture<QueueRemovalUndo?> {
            if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex ||
                player.getMediaItemAt(index).mediaId != item.mediaId ||
                queueOccurrenceKey(player.getMediaItemAt(index)) != queueOccurrenceKey(item))
                return Futures.immediateFuture(null)
            val request = QueueRemovalRequest(index, item.mediaId, queueOccurrenceKey(item))
            if (player is MediaController) {
                if (!player.availableSessionCommands.contains(REMOVE_QUEUE_ITEM)) return Futures.immediateFuture(null)
                return Futures.transform(player.sendCustomCommand(REMOVE_QUEUE_ITEM, request.toBundle()), { result ->
                    if (result?.resultCode != SessionResult.RESULT_SUCCESS) null
                    else QueueRemovalUndo(player, QueueRemovalTicket.fromBundle(result.extras))
                }, MoreExecutors.directExecutor())
            }
            val removed = request.applyTo(player) ?: return Futures.immediateFuture(null)
            return Futures.immediateFuture(QueueRemovalUndo(player, QueueRemovalTicket.fromBundle(removed)))
        }

        suspend fun removeAwait(player: Player, index: Int, item: MediaItem): QueueRemovalUndo? =
            suspendCancellableCoroutine { continuation ->
                val future = remove(player, index, item)
                future.addListener({
                    continuation.resume(runCatching { future.get() }.getOrNull())
                }, MoreExecutors.directExecutor())
            }
    }
}

/** Same-UID removal of the row the app showed: its position, song and occurrence must still match. */
internal class QueueRemovalRequest(private val index: Int, private val mediaId: String, private val key: String?) {
    fun toBundle() = Bundle().apply {
        putInt(INDEX, index); putString(MEDIA_ID, mediaId); key?.let { putString(KEY, it) }
    }

    /** On the player's thread. Null refuses without change; otherwise removed, with a ticket bundle
     * only when Undo can prove ownership (an empty bundle offers none). */
    fun applyTo(player: Player): Bundle? {
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
            index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex) return null
        val target = player.getMediaItemAt(index)
        if (target.mediaId != mediaId || queueOccurrenceKey(target) != key) return null
        val shuffled = queueShuffledOrder(player)
        player.removeMediaItem(index)
        return QueueRemovalTicket.capture(player, index, target, shuffled)?.toBundle() ?: Bundle()
    }

    companion object {
        private const val INDEX = "index"
        private const val MEDIA_ID = "mediaId"
        private const val KEY = "key"
        fun fromBundle(bundle: Bundle): QueueRemovalRequest? = runCatching {
            val index = bundle.getInt(INDEX, -1)
            val mediaId = requireNotNull(bundle.getString(MEDIA_ID))
            val key = bundle.getString(KEY)
            require(index in 0 until SavedQueueLimits().items && mediaId.isNotEmpty() && mediaId.length <= 16 * 1024)
            // Occurrence keys are canonical UUIDs (queueOccurrenceKey); anything else names no row.
            require(key == null || java.util.UUID.fromString(key).toString() == key)
            QueueRemovalRequest(index, mediaId, key)
        }.getOrNull()
    }
}

/**
 * Stateless Undo for one removal, held by the app and returned to the service. Its size doesn't
 * grow with the surviving songs' text: a count and digest identify them, and the original shuffled
 * positions are one int each. The service revalidates after asynchronous saved-copy admission.
 */
internal class QueueRemovalTicket private constructor(private val index: Int, val item: MediaItem,
    private val count: Int, private val digest: ByteArray, private val shuffled: IntArray) {

    /** The exact surviving occurrences, still without the removed one. */
    fun matches(player: Player): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) || player.mediaItemCount != count) return false
        val now = queueOccurrences(player) ?: return false
        val key = queueOccurrenceKey(item)
        return now.none { it.second == key } && MessageDigest.isEqual(queueSnapshotDigest(now), digest)
    }

    fun apply(player: Player, exo: ExoPlayer, admitted: MediaItem): Boolean {
        if (!matches(player) || admitted.mediaId != item.mediaId ||
            queueOccurrenceKey(admitted) != queueOccurrenceKey(item)) return false
        val keptOrder = shuffled.filter { it != index }.map { if (it > index) it - 1 else it }
        // A deliberate reshuffle since removal keeps its new order; ordinary Undo restores exactly.
        val restoreShuffle = queueShuffledOrder(player) == keptOrder
        // Fresh identity clears only the removed row's dismissed UI state; surviving rows keep theirs.
        try { player.addMediaItem(index, queueOccurrence(admitted)) } catch (_: PlaybackQueueLimit) { return false }
        if (restoreShuffle) exo.setShuffleOrder(QueueShuffleOrder.restored(shuffled.toList()))
        return true
    }

    fun toBundle() = Bundle().apply {
        putInt(INDEX, index)
        putBundle(ITEM, item.toBundle())
        putInt(COUNT, count)
        putByteArray(DIGEST, digest.clone())
        putIntArray(SHUFFLE, shuffled.clone())
    }

    companion object {
        private const val INDEX = "index"
        private const val ITEM = "item"
        private const val COUNT = "count"
        private const val DIGEST = "digest"
        private const val SHUFFLE = "shuffle"
        private const val DIGEST_BYTES = 32

        /** After removal, on the player's thread; [shuffled] is the order captured just before it. */
        fun capture(player: Player, index: Int, removed: MediaItem, shuffled: List<Int>): QueueRemovalTicket? {
            val key = queueOccurrenceKey(removed) ?: return null
            val uri = restoreUrl(removed.mediaId) ?: return null
            val survivors = queueOccurrences(player) ?: return null
            if (survivors.size !in 1 until SavedQueueLimits().items || survivors.any { it.second == key } ||
                shuffled.size != survivors.size + 1) return null
            return QueueRemovalTicket(index, removed.buildUpon().setUri(uri).build(), survivors.size,
                queueSnapshotDigest(survivors), shuffled.toIntArray())
        }

        fun fromBundle(bundle: Bundle): QueueRemovalTicket? = runCatching {
            val count = bundle.getInt(COUNT, -1)
            val index = bundle.getInt(INDEX, -1)
            require(count in 1 until SavedQueueLimits().items && index in 0..count)
            val digest = requireNotNull(bundle.getByteArray(DIGEST)).clone()
            require(digest.size == DIGEST_BYTES)
            val shuffled = requireNotNull(bundle.getIntArray(SHUFFLE)).clone()
            require(shuffled.size == count + 1 && shuffled.sorted() == (0..count).toList())
            val item = MediaItem.fromBundle(requireNotNull(bundle.getBundle(ITEM)))
            requireNotNull(queueOccurrenceKey(item))
            val uri = requireNotNull(restoreUrl(item.mediaId))
            QueueRemovalTicket(index, item.buildUpon().setUri(uri).build(), count, digest, shuffled)
        }.getOrNull()
    }
}
