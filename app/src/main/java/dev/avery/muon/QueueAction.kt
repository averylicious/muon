package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/** A queue action belongs to this exact controller snapshot, including duplicate occurrences. */
internal data class QueueActionStamp(val revision: Int, val timeline: Timeline, val current: Int, val shuffle: Boolean)

/** Call on the player's application thread immediately before a row action. No stale index is read. */
internal fun queueEntryCurrent(player: Player, stamp: QueueActionStamp?, revision: Int,
    index: Int, item: MediaItem): Boolean = stamp != null && revision == stamp.revision &&
    player.currentTimeline === stamp.timeline && player.currentMediaItemIndex == stamp.current &&
    player.shuffleModeEnabled == stamp.shuffle && index in 0 until player.mediaItemCount &&
    player.getMediaItemAt(index) == item
