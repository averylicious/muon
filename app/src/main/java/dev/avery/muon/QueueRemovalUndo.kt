package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** One removal's short-lived ownership, used and closed on the player's application thread. */
internal class QueueRemovalUndo private constructor(private val player: Player, private val index: Int,
    private val restore: MediaItem?, private val remaining: List<Pair<String, String>>?) : AutoCloseable {
    private var closed = false
    private var attempted = false
    // Next up removal retains the playing entry. Empty/ambiguous playlists cannot prove ownership.
    val restorable: Boolean get() = restore != null && queueOccurrenceKey(restore) != null &&
        !remaining.isNullOrEmpty() && remaining.none { it.second == queueOccurrenceKey(restore) }

    /** Normal controller acknowledgements preserve keys; a newly built/reordered queue does not. */
    fun undo(): Boolean {
        if (closed || attempted) return false
        attempted = true
        if (!restorable || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
            queueOccurrences(player) != remaining) return false
        player.addMediaItem(index.coerceAtMost(player.mediaItemCount), requireNotNull(restore))
        return true
    }

    override fun close() { closed = true }

    companion object {
        /** Refuses a stale/out-of-range row before reading it; no suspended work occurs here. */
        fun remove(player: Player, index: Int, item: MediaItem): QueueRemovalUndo? {
            if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex ||
                player.getMediaItemAt(index).mediaId != item.mediaId ||
                queueOccurrenceKey(player.getMediaItemAt(index)) != queueOccurrenceKey(item)) return null
            val restored = restoreUrl(item.mediaId)?.let { item.buildUpon().setUri(it).build() }
            player.removeMediaItem(index)
            return QueueRemovalUndo(player, index, restored, queueOccurrences(player))
        }
    }
}
