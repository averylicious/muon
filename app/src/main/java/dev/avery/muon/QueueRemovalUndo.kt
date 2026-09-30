package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/** One removal's short-lived ownership, used and closed on the player's application thread. */
internal class QueueRemovalUndo private constructor(private val player: Player, private val index: Int,
    private val restore: MediaItem?) : AutoCloseable {
    private var generation = 0
    private var expectedGeneration = 0
    private var remaining = emptyList<MediaItem>()
    private var closed = false
    private var attempted = false
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) generation++
        }
    }

    // QueueScreen removes Next up, so an unchanged queue always retains its playing entry. Refuse
    // an empty result rather than treating a shared empty timeline as proof of surviving ownership.
    val restorable: Boolean get() = restore != null && remaining.isNotEmpty()

    /** A new playlist, including one with identical IDs, ends Undo. Position/source updates do not. */
    fun undo(): Boolean {
        if (closed || attempted) return false
        attempted = true
        if (!restorable || generation != expectedGeneration ||
            !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
            items(player) != remaining) return false
        player.addMediaItem(index.coerceAtMost(player.mediaItemCount), requireNotNull(restore))
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        player.removeListener(listener)
    }

    companion object {
        /** Refuses a stale/out-of-range row before reading it; no suspended work occurs here. */
        fun remove(player: Player, index: Int, item: MediaItem): QueueRemovalUndo? {
            if (!player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS) ||
                index !in 0 until player.mediaItemCount || index == player.currentMediaItemIndex ||
                player.getMediaItemAt(index) != item) return null
            val restored = restoreUrl(item.mediaId)?.let { item.buildUpon().setUri(it).build() }
            val undo = QueueRemovalUndo(player, index, restored)
            player.addListener(undo.listener)
            try {
                player.removeMediaItem(index)
                undo.expectedGeneration = undo.generation
                undo.remaining = items(player)
                return undo
            } catch (failure: Exception) {
                undo.close()
                throw failure
            }
        }
        private fun items(player: Player) = List(player.mediaItemCount, player::getMediaItemAt)
    }
}
