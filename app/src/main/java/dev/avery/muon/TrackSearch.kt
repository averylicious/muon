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

/**
 * What to show when a search produces no rows. "Nothing matches" must not appear while a search is
 * still running, and it quotes the query that actually finished rather than the one being typed.
 */
internal fun searchEmptyText(query: String, searching: Boolean, completed: String): String = when {
    query.isBlank() -> "Your collection, one search away."
    searching || completed.isBlank() -> "Searching…"
    else -> "Nothing matches \u201c$completed\u201d."
}
