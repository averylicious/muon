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
    val sourceRows = ArrayList<Int>(count)
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
        sourceRows += index
    }
    return if (start < 0) null else SavedQueuePlan(items, start, sourceRows)
}

/**
 * Maps each live queue occurrence UUID back to the source list row that created it (#783).
 * Retaining the source track IDs guards against stale highlights if Tauon reorders the list.
 */
internal class LiveQueueOrigin(
    val sourceKey: String,
    private val trackIds: LongArray,
    private val occurrenceRows: Map<String, Int>,
) {
    fun rowFor(occurrenceKey: String): Int? = occurrenceRows[occurrenceKey]

    fun matchesTracks(tracks: List<TauonTrack>): Boolean {
        if (tracks.size != trackIds.size) return false
        for (i in tracks.indices) if (tracks[i].id != trackIds[i]) return false
        return true
    }

    companion object {
        fun from(sourceKey: String, tracks: List<TauonTrack>, plan: SavedQueuePlan): LiveQueueOrigin {
            val ids = LongArray(tracks.size) { tracks[it].id }
            val rows = HashMap<String, Int>(plan.items.size)
            for (i in plan.items.indices) {
                val row = plan.sourceRows.getOrNull(i) ?: continue
                queueOccurrenceKey(plan.items[i])?.let { rows[it] = row }
            }
            return LiveQueueOrigin(sourceKey, ids, rows)
        }
    }
}

/**
 * The single row index in [tracks] currently playing, or -1 if none or ambiguous (#783).
 *
 * When [activeOrigin] matches [sourceKey] and the list's track order, the playing item's unique
 * occurrence UUID pinpoints its exact row even when the same song appears multiple times.
 * Otherwise (viewing a different screen, playing an ad-hoc `Play next` insertion, or after process
 * restart), highlight only if the playing track appears uniquely once in [tracks].
 */
internal fun activeTrackIndex(
    tracks: List<TauonTrack>,
    endpoint: ServerEndpoint?,
    currentMediaId: String?,
    currentOccurrenceKey: String?,
    sourceKey: String,
    activeOrigin: LiveQueueOrigin?,
): Int {
    val origin = endpoint?.origin ?: return -1
    val mediaId = currentMediaId ?: return -1
    if (activeOrigin != null && currentOccurrenceKey != null &&
        activeOrigin.sourceKey == sourceKey && activeOrigin.matchesTracks(tracks)) {
        val row = activeOrigin.rowFor(currentOccurrenceKey)
        if (row != null && row in tracks.indices && "$origin/${tracks[row].id}" == mediaId) return row
    }
    var match = -1
    for (i in tracks.indices) {
        if ("$origin/${tracks[i].id}" != mediaId) continue
        if (match != -1) return -1
        match = i
    }
    return match
}
