@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.MediaItem

/**
 * Bound incoming app queue items before snapshot/reference-set/background allocation (#253).
 * Same2048/4MiB logical text policy as saved preparation; count current app metadata/locators,
 * occurrence IDs and encoded song/artwork bytes. Not a Parcel/heap or arbitrary opaque-extras bound.
 * Never return a prefix: a refusal must not replace the existing player queue with an empty list.
 */
internal fun playbackInputFits(items: List<MediaItem>, limits: SavedQueueLimits = SavedQueueLimits()): Boolean {
    require(limits.items > 0 && limits.textBytes >= 0)
    if (items.size > limits.items) return false
    val budget = PlaybackItemBudget(limits)
    return items.all(budget::add)
}

/** Shared accounting for streamed preparation and a final player queue, without copying a timeline. */
internal class PlaybackItemBudget(private val limits: SavedQueueLimits = SavedQueueLimits()) {
    private var count = 0
    private var left = limits.textBytes
    init { require(limits.items > 0 && limits.textBytes >= 0) }
    fun add(item: MediaItem): Boolean {
        if (count >= limits.items) return false
        val metadata = item.mediaMetadata
        val binary = (metadata.extras?.getByteArray(SONG_EXTRA)?.size?.toLong() ?: 0L) +
            (metadata.artworkData?.size?.toLong() ?: 0L)
        if (binary > left) return false
        val fields = listOf(item.mediaId, item.localConfiguration?.uri?.toString().orEmpty(),
            metadata.title, metadata.artist, metadata.albumTitle, metadata.albumArtist,
            metadata.artworkUri?.toString().orEmpty(), queueOccurrenceKey(item).orEmpty())
        if (fields.sumOf { it?.length?.toLong() ?: 0L } > left - binary) return false
        val bytes = binary + fields.sumOf { it?.toString()?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
        if (bytes > left) return false
        left -= bytes
        count++
        return true
    }
}
