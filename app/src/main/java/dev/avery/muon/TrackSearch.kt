package dev.avery.muon

/** Keystrokes within this window reuse the previous search instead of refiltering. */
internal const val SEARCH_DEBOUNCE_MS = 120L

/** Filters the loaded library. Called off the main thread; keep it allocation-light. */
internal fun searchTracks(tracks: List<TauonTrack>, query: String): List<TauonTrack> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return tracks.filter {
        it.title.contains(needle, ignoreCase = true) ||
            it.artist.contains(needle, ignoreCase = true) ||
            it.album.contains(needle, ignoreCase = true)
    }
}
