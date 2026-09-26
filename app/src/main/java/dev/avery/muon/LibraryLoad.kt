package dev.avery.muon

/**
 * What one library load produced (#53): the playlists to show, their songs, and how many could not
 * be fetched this time. Loading used to be all-or-nothing, so one timed-out playlist threw away every
 * other one; now each playlist stands on its own.
 */
internal data class LibraryLoad(val playlists: List<TauonPlaylist>, val tracks: Map<String, List<TauonTrack>>,
    val failed: Int)

/**
 * Combines the playlists Tauon listed with the songs that did load. A playlist that failed keeps the
 * songs it had in [previous] — the same server's last library — so a refresh never loses what was
 * already on screen; one with nothing to keep is left out, rather than shown as empty. Tauon's order
 * is kept.
 */
internal fun combineLoad(lists: List<TauonPlaylist>, loaded: Map<String, List<TauonTrack>>,
    previous: Map<String, List<TauonTrack>>?): LibraryLoad {
    val tracks = linkedMapOf<String, List<TauonTrack>>()
    var failed = 0
    lists.forEach { list ->
        val songs = loaded[list.id] ?: previous?.get(list.id).also { failed++ }
        if (songs != null) tracks[list.id] = songs
    }
    return LibraryLoad(lists.filter { it.id in tracks }, tracks, failed)
}

/** The note shown when some playlists did not load, with Retry beside it. */
internal fun partialLoadMessage(failed: Int, total: Int): String =
    "$failed of $total ${if (total == 1) "playlist" else "playlists"} didn't load. Retry to try again."
