@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.UUID

/** Snackbar Undo identifies this insertion, not another occurrence of the same song. */
internal class QueueInsertion(val item: MediaItem, val token: String)
internal val INSERT_QUEUE_ITEM = SessionCommand("dev.avery.muon.INSERT_QUEUE_ITEM", Bundle.EMPTY)
internal val UNDO_QUEUE_INSERTION = SessionCommand("dev.avery.muon.UNDO_QUEUE_INSERTION", Bundle.EMPTY)
private const val INSERTION_EXTRA = "dev.avery.muon.queueInsertion"
private const val UNDO_TOKEN = "token"

internal fun queueInsertion(item: MediaItem): QueueInsertion {
    val token = UUID.randomUUID().toString()
    val extras = item.mediaMetadata.extras?.let(::Bundle) ?: Bundle()
    extras.putString(INSERTION_EXTRA, token)
    return QueueInsertion(item.buildUpon().setMediaMetadata(
        item.mediaMetadata.buildUpon().setExtras(extras).build()).build(), token)
}

/** A live insertion belongs to the queue occurrence the user saw. */
internal class QueueInsertionRequest private constructor(
    val item: MediaItem,
    private val anchor: String,
    private val next: Boolean,
) {
    fun toBundle() = Bundle().apply {
        putBundle("item", item.toBundle())
        putString("anchor", anchor)
        putBoolean("next", next)
    }

    fun applyTo(player: Player, exo: ExoPlayer?, admitted: MediaItem): Boolean {
        if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
        val token = insertionToken(admitted) ?: return false
        val count = player.mediaItemCount
        var anchorIndex = -1
        for (i in 0 until count) {
            val queued = player.getMediaItemAt(i)
            if (insertionToken(queued) == token) return false
            if (queueOccurrenceKey(queued) == anchor) {
                if (anchorIndex != -1) return false
                anchorIndex = i
            }
        }
        if (anchorIndex == -1 || (next && anchorIndex != player.currentMediaItemIndex)) return false
        val index = if (next) anchorIndex + 1 else count
        // A native append goes to the end of shuffle. If the current item is last in playlist
        // order, Play next and Add have the same native index but need different shuffle positions.
        val shuffle = if (next && index == count && player.shuffleModeEnabled) {
            if (exo == null) return false
            val order = queueShuffledOrder(player).toMutableList()
            val afterAnchor = order.indexOf(anchorIndex)
            if (afterAnchor == -1) return false
            order.add(afterAnchor + 1, index)
            QueueShuffleOrder.restored(order)
        } else null
        try {
            player.addMediaItem(index, admitted)
        } catch (_: PlaybackQueueLimit) {
            return false
        }
        if (shuffle != null) checkNotNull(exo).setShuffleOrder(shuffle)
        return true
    }

    companion object {
        fun capture(player: Player, insertion: QueueInsertion, next: Boolean): QueueInsertionRequest? {
            val anchor = player.currentMediaItem?.let(::queueOccurrenceKey) ?: return null
            return QueueInsertionRequest(insertion.item, anchor, next)
        }

        fun fromBundle(args: Bundle): QueueInsertionRequest? = runCatching {
            val anchor = requireNotNull(args.getString("anchor"))
            require(UUID.fromString(anchor).toString() == anchor)
            val item = MediaItem.fromBundle(requireNotNull(args.getBundle("item")))
            require(queueOccurrenceKey(item) != null && insertionToken(item) != null)
            require(!isSavedHandle(item.mediaId))
            val uri = requireNotNull(restoreUrl(item.mediaId))
            QueueInsertionRequest(item.buildUpon().setUri(uri).build(), anchor, args.getBoolean("next"))
        }.getOrNull()
    }
}

private fun insertionToken(item: MediaItem): String? =
    item.mediaMetadata.extras?.getString(INSERTION_EXTRA)?.takeIf {
        runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
    }

internal suspend fun QueueInsertionRequest.confirm(player: MediaController): Boolean =
    player.confirmQueueCommand(INSERT_QUEUE_ITEM, toBundle())

internal suspend fun QueueInsertion.undo(player: MediaController): Boolean =
    player.confirmQueueCommand(UNDO_QUEUE_INSERTION, Bundle().apply { putString(UNDO_TOKEN, token) })

/** The receipt can arrive before the controller's timeline update. Undo uses the service queue. */
internal fun applyInsertionUndo(player: Player, args: Bundle): Boolean {
    val token = runCatching {
        args.getString(UNDO_TOKEN)?.takeIf { UUID.fromString(it).toString() == it }
    }.getOrNull() ?: return false
    return undoQueueInsertion(player, token)
}

private suspend fun MediaController.confirmQueueCommand(command: SessionCommand, args: Bundle): Boolean =
    suspendCancellableCoroutine { continuation ->
        if (!isConnected || !availableSessionCommands.contains(command)) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val future = sendCustomCommand(command, args)
        future.addListener({
            continuation.resume(runCatching { future.get().resultCode == SessionResult.RESULT_SUCCESS }
                .getOrDefault(false))
        }, MoreExecutors.directExecutor())
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/** Only the exact surviving insertion may be removed; an absent/ambiguous token is a no-op. */
internal fun undoQueueInsertion(player: Player, insertion: QueueInsertion): Boolean =
    undoQueueInsertion(player, insertion.token)

private fun undoQueueInsertion(player: Player, token: String): Boolean {
    if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
    var index = -1
    for (i in 0 until player.mediaItemCount) {
        if (player.getMediaItemAt(i).mediaMetadata.extras?.getString(INSERTION_EXTRA) != token) continue
        if (index != -1) return false
        index = i
    }
    if (index == -1) return false
    player.removeMediaItem(index)
    return true
}
