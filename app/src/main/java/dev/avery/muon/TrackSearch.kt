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

/** How many artists a search lists above its songs; the songs below already hold the rest. */
internal const val SEARCH_ARTIST_LIMIT = 4

/** How many albums a search offers in its row. */
internal const val SEARCH_ALBUM_LIMIT = 12

/**
 * The artists whose name holds the query, those whose name starts with it first, then the ones with
 * more songs. Stable otherwise, so equal matches keep the order they were given in.
 */
internal fun searchArtists(artists: List<LibraryArtist>, query: String, limit: Int = SEARCH_ARTIST_LIMIT): List<LibraryArtist> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return artists.filter { it.name.contains(needle, ignoreCase = true) }
        .sortedWith(compareBy<LibraryArtist> { !it.name.trim().startsWith(needle, ignoreCase = true) }.thenByDescending { it.tracks.size })
        .take(limit)
}

/** The albums whose title or album artist holds the query, titles starting with it first. */
internal fun searchAlbums(albums: List<LibraryAlbum>, query: String, limit: Int = SEARCH_ALBUM_LIMIT): List<LibraryAlbum> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    return albums.filter { it.title.contains(needle, ignoreCase = true) || it.artist.contains(needle, ignoreCase = true) }
        .sortedBy { !it.title.trim().startsWith(needle, ignoreCase = true) }
        .take(limit)
}
