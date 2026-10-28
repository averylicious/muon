@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

/** Full selection or refusal, never a prefix; count before filtering/mapping or changing player modes.
 * [selectedIndex] names the clicked row in the original list, before unplayable entries are removed.
 * An accompanying [selected] ID must still match that row; ID-only callers retain first-match behavior.
 */
internal fun prepareLiveQueue(tracks: List<TauonTrack>, endpoint: ServerEndpoint, selected: Long? = null,
    limits: SavedQueueLimits = SavedQueueLimits(), selectedIndex: Int? = null): SavedQueuePlan? {
    require(limits.items > 0 && limits.textBytes >= 0)
    var count = 0
    for (track in tracks) if (track.playable && ++count > limits.items) throw PlaybackQueueLimit()
    if (count == 0) return null
    if (selectedIndex != null && (selectedIndex !in tracks.indices || !tracks[selectedIndex].playable ||
            (selected != null && tracks[selectedIndex].id != selected))) return null
    val budget = PlaybackItemBudget(limits)
    val items = ArrayList<androidx.media3.common.MediaItem>(count)
    var start = if (selected == null && selectedIndex == null) 0 else -1
    for ((index, track) in tracks.withIndex()) {
        if (!track.playable) continue
        // Song extras retain all original text, even when display credits are formatted. Refuse
        // giant records before encodeSong/Bundles; final accounting below includes URLs and extras.
        if (listOf(track.title, track.artist, track.album, track.albumArtist, track.trackNumber)
            .sumOf { it.length.toLong() } > limits.textBytes) throw PlaybackQueueLimit()
        val item = track.mediaItem(endpoint)
        if (!budget.add(item)) throw PlaybackQueueLimit()
        val chosen = if (selectedIndex != null) index == selectedIndex else track.id == selected
        if (start < 0 && chosen) start = items.size
        items += item
    }
    return if (start < 0) null else SavedQueuePlan(items, start)
}
