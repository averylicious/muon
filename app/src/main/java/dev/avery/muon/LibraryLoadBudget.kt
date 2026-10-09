package dev.avery.muon

import java.io.IOException

/** Retained record limits, not a byte-exact heap/DOM or Binder guarantee (#253). */
internal data class LibraryLoadLimits(
    val playlists: Int = 2_048,
    val entries: Int = 50_000,
    val encodedMetadataBytes: Long = 16L * 1024 * 1024,
)

private val LIBRARY_LOAD_LIMITS = LibraryLoadLimits()

internal class LibraryResourceLimit(message: String =
    "Library exceeds Muon's resource limits (2,048 playlists, 50,000 playlist entries or " +
        "16 MiB encoded metadata). Reduce the server library and retry.") : IOException(message)

internal fun requireLibraryPlaylistCount(count: Int, limits: LibraryLoadLimits = LIBRARY_LOAD_LIMITS) {
    if (count > limits.playlists) throw LibraryResourceLimit()
}

internal fun requireLibraryEntryCount(count: Int, limits: LibraryLoadLimits = LIBRARY_LOAD_LIMITS) {
    if (count > limits.entries) throw LibraryResourceLimit()
}

/**
 * Used serially on IO during one load. Count occurrences, not distinct IDs: playlists hold separately
 * projected records even when their songs coincide. A refused batch never changes the counters.
 */
internal class LibraryLoadBudget(lists: List<TauonPlaylist>, private val limits: LibraryLoadLimits = LIBRARY_LOAD_LIMITS) {
    private var entries = 0
    private var remaining = limits.encodedMetadataBytes

    init {
        require(limits.playlists >= 0 && limits.entries >= 0 && limits.encodedMetadataBytes >= 0)
        requireLibraryPlaylistCount(lists.size, limits)
        for (list in lists) {
            // UTF-8 needs at least one byte per UTF-16 code unit; reject before allocating encodings.
            if (list.id.length.toLong() + list.name.length > remaining) throw LibraryResourceLimit()
            val bytes = list.id.toByteArray(Charsets.UTF_8).size.toLong() + list.name.toByteArray(Charsets.UTF_8).size
            if (bytes > remaining) throw LibraryResourceLimit()
            remaining -= bytes
        }
    }

    fun add(songs: List<TauonTrack>) {
        if (songs.size > limits.entries - entries) throw LibraryResourceLimit()
        var left = remaining
        for (song in songs) {
            val characters = listOf(song.title, song.artist, song.album, song.albumArtist, song.trackNumber)
                .sumOf { it.length.toLong() }
            if (characters > left) throw LibraryResourceLimit()
            val bytes = encodeSong(song).size.toLong()
            if (bytes > left) throw LibraryResourceLimit()
            left -= bytes
        }
        entries += songs.size
        remaining = left
    }
}

/** Also account for old playlists retained after a failed fetch, before any new library is published. */
internal fun combineBudgetedLoad(lists: List<TauonPlaylist>, loaded: Map<String, List<TauonTrack>>,
    previous: Map<String, List<TauonTrack>>?, limits: LibraryLoadLimits = LIBRARY_LOAD_LIMITS): LibraryLoad {
    val result = combineLoad(lists, loaded, previous)
    val budget = LibraryLoadBudget(result.playlists, limits)
    result.tracks.values.forEach(budget::add)
    return result
}
