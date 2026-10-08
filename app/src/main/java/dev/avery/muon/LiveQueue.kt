@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

/** Full selection or refusal, never a prefix; count before filtering/mapping or changing player modes. */
internal fun prepareLiveQueue(tracks: List<TauonTrack>, endpoint: ServerEndpoint, selected: Long? = null,
    limits: SavedQueueLimits = SavedQueueLimits()): SavedQueuePlan? {
    require(limits.items > 0 && limits.textBytes >= 0)
    var count = 0
    for (track in tracks) if (track.playable && ++count > limits.items) throw PlaybackQueueLimit()
    if (count == 0) return null
    val budget = PlaybackItemBudget(limits)
    val items = ArrayList<androidx.media3.common.MediaItem>(count)
    var start = if (selected == null) 0 else -1
    for (track in tracks) {
        if (!track.playable) continue
        // Song extras retain all original text, even when display credits are formatted. Refuse
        // giant records before encodeSong/Bundles; final accounting below includes URLs and extras.
        if (listOf(track.title, track.artist, track.album, track.albumArtist, track.trackNumber)
            .sumOf { it.length.toLong() } > limits.textBytes) throw PlaybackQueueLimit()
        val item = track.mediaItem(endpoint)
        if (!budget.add(item)) throw PlaybackQueueLimit()
        if (start < 0 && track.id == selected) start = items.size
        items += item
    }
    return if (start < 0) null else SavedQueuePlan(items, start)
}
