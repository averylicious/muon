package dev.avery.muon

/**
 * Stable list keys for a track list. The previous key included the list index, so every item lost
 * its identity whenever the list changed. Track IDs are unique per server but a playlist may hold
 * the same track twice, so repeats are numbered rather than prefixed with a position.
 */
internal fun trackKeys(tracks: List<TauonTrack>): List<String> {
    val seen = HashMap<Long, Int>()
    return tracks.map { track ->
        val occurrence = (seen[track.id] ?: 0) + 1
        seen[track.id] = occurrence
        if (occurrence == 1) track.id.toString() else "${track.id}#$occurrence"
    }
}
