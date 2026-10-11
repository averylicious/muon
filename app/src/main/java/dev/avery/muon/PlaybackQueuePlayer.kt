@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal class PlaybackQueueLimit : IllegalArgumentException("That queue has too many items or too much metadata. Choose fewer songs.")

/** Check the actual resulting queue at mutation time, including repeated Add and replacement races. */
internal fun playbackEditFits(player: Player, removedFrom: Int, removedTo: Int,
    items: List<MediaItem>, limits: SavedQueueLimits = SavedQueueLimits()): Boolean {
    val count = player.mediaItemCount
    require(removedFrom in 0..count && removedTo in removedFrom..count)
    if (count.toLong() - (removedTo - removedFrom) + items.size > limits.items) return false
    val budget = PlaybackItemBudget(limits)
    for (i in 0 until count) if (i !in removedFrom until removedTo && !budget.add(player.getMediaItemAt(i))) return false
    return items.all(budget::add)
}

/** ForwardingPlayer convenience methods delegate directly; every growing overload needs its own guard. */
internal class PlaybackQueuePlayer(player: Player, private val limits: SavedQueueLimits = SavedQueueLimits(),
    private val refused: () -> Unit = {}) : ForwardingPlayer(player) {
    private fun check(items: List<MediaItem>, from: Int = 0, to: Int = mediaItemCount) {
        if (!playbackEditFits(this, from, to, items, limits)) {
            refused()
            throw PlaybackQueueLimit()
        }
    }
    override fun setMediaItems(mediaItems: List<MediaItem>) { check(mediaItems); super.setMediaItems(mediaItems) }
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        check(mediaItems); super.setMediaItems(mediaItems, resetPosition)
    }
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        check(mediaItems); super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem) { check(listOf(mediaItem)); super.setMediaItem(mediaItem) }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        check(listOf(mediaItem)); super.setMediaItem(mediaItem, startPositionMs)
    }
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
        check(listOf(mediaItem)); super.setMediaItem(mediaItem, resetPosition)
    }
    override fun addMediaItem(mediaItem: MediaItem) {
        check(listOf(mediaItem), 0, 0); super.addMediaItem(mediaItem)
    }
    override fun addMediaItem(index: Int, mediaItem: MediaItem) {
        check(listOf(mediaItem), 0, 0); super.addMediaItem(index, mediaItem)
    }
    override fun addMediaItems(mediaItems: List<MediaItem>) {
        check(mediaItems, 0, 0); super.addMediaItems(mediaItems)
    }
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) {
        check(mediaItems, 0, 0); super.addMediaItems(index, mediaItems)
    }
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
        check(listOf(mediaItem), index, minOf(index + 1, mediaItemCount)); super.replaceMediaItem(index, mediaItem)
    }
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) {
        check(mediaItems, fromIndex, minOf(toIndex, mediaItemCount)); super.replaceMediaItems(fromIndex, toIndex, mediaItems)
    }
}
