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
import kotlin.coroutines.resume

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

/** One removal's short-lived ownership, used and closed on the player's application thread. */
internal class QueueRemovalUndo private constructor(private val player: Player, private val index: Int,
    private val restore: MediaItem?, private val remaining: List<Pair<String, String>>?,
    private val shuffled: List<Int>) : AutoCloseable {
    private var closed = false
    private var attempted = false
    val restorable: Boolean get() = restore != null && queueOccurrenceKey(restore) != null &&
        !remaining.isNullOrEmpty() && remaining.none { it.second == queueOccurrenceKey(restore) } &&
        shuffled.size == remaining.size + 1

    /** The service acknowledges both insertion and shuffled placement, not just the local mask. */
    fun undo(): ListenableFuture<Boolean> {
        if (closed || attempted) return Futures.immediateFuture(false)
        attempted = true
        if (!restorable || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
            queueOccurrences(player) != remaining) return Futures.immediateFuture(false)
        val request = QueueRemovalRestore(index, requireNotNull(restore), requireNotNull(remaining), shuffled)
        if (player is MediaController) {
            if (!player.availableSessionCommands.contains(RESTORE_QUEUE_REMOVAL)) return Futures.immediateFuture(false)
            return Futures.transform(player.sendCustomCommand(RESTORE_QUEUE_REMOVAL, request.toBundle()),
                { it?.resultCode == SessionResult.RESULT_SUCCESS }, MoreExecutors.directExecutor())
        }
        val exo = player as? ExoPlayer ?: return Futures.immediateFuture(false)
        return Futures.immediateFuture(request.apply(player, exo, request.item))
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
        fun remove(player: Player, index: Int, item: MediaItem): QueueRemovalUndo? {
            if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex ||
                player.getMediaItemAt(index).mediaId != item.mediaId ||
                queueOccurrenceKey(player.getMediaItemAt(index)) != queueOccurrenceKey(item)) return null
            val restored = restoreUrl(item.mediaId)?.let { item.buildUpon().setUri(it).build() }
            val shuffled = queueShuffledOrder(player)
            player.removeMediaItem(index)
            return QueueRemovalUndo(player, index, restored, queueOccurrences(player), shuffled)
        }
    }
}

/** Same-UID session request. Revalidate after asynchronous saved-copy admission before mutating. */
internal class QueueRemovalRestore(val index: Int, val item: MediaItem,
    private val remaining: List<Pair<String, String>>, private val shuffled: List<Int>) {
    fun matches(player: Player): Boolean = player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) &&
        index in 0..player.mediaItemCount && queueOccurrences(player) == remaining

    fun apply(player: Player, exo: ExoPlayer, admitted: MediaItem): Boolean {
        if (!matches(player) || admitted.mediaId != item.mediaId ||
            queueOccurrenceKey(admitted) != queueOccurrenceKey(item)) return false
        val keptOrder = shuffled.filter { it != index }.map { if (it > index) it - 1 else it }
        // A deliberate reshuffle since removal keeps its new order; ordinary Undo restores exactly.
        val restoreShuffle = queueShuffledOrder(player) == keptOrder
        // Fresh identity clears only the removed row's dismissed UI state; surviving rows keep theirs.
        player.addMediaItem(index, queueOccurrence(admitted))
        if (restoreShuffle) exo.setShuffleOrder(QueueShuffleOrder.restored(shuffled))
        return true
    }

    fun toBundle() = Bundle().apply {
        putInt("index", index)
        putBundle("item", item.toBundle())
        putStringArrayList("ids", ArrayList(remaining.map { it.first }))
        putStringArrayList("keys", ArrayList(remaining.map { it.second }))
        putIntArray("shuffle", shuffled.toIntArray())
    }

    companion object {
        fun fromBundle(bundle: Bundle): QueueRemovalRestore? = runCatching {
            val index = bundle.getInt("index", -1)
            val ids = requireNotNull(bundle.getStringArrayList("ids"))
            val keys = requireNotNull(bundle.getStringArrayList("keys"))
            require(ids.size in 1 until SavedQueueLimits().items && ids.size == keys.size)
            require(index in 0..ids.size && keys.toSet().size == keys.size)
            require(ids.sumOf { it.length.toLong() } + keys.sumOf { it.length.toLong() } <= SavedQueueLimits().textBytes)
            val shuffled = requireNotNull(bundle.getIntArray("shuffle")).toList()
            require(shuffled.size == ids.size + 1 && shuffled.toSet() == (0..ids.size).toSet())
            val item = MediaItem.fromBundle(requireNotNull(bundle.getBundle("item")))
            val key = requireNotNull(queueOccurrenceKey(item))
            require(key !in keys)
            val uri = requireNotNull(restoreUrl(item.mediaId))
            QueueRemovalRestore(index, item.buildUpon().setUri(uri).build(), ids.zip(keys), shuffled)
        }.getOrNull()
    }
}
