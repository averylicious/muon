package dev.avery.muon

import java.io.IOException

/** Retained record limits, not a byte-exact heap/DOM or Binder guarantee (#253). */
internal data class LibraryLoadLimits(
    val playlists: Int = 2_048,
    val entries: Int = 50_000,
    val encodedMetadataBytes: Long = 16L * 1024 * 1024,
    val artistMemberships: Int = 100_000,
)

private val LIBRARY_LOAD_LIMITS = LibraryLoadLimits()

internal class LibraryResourceLimit(message: String =
    "Library exceeds Muon's resource limits (2,048 playlists, 50,000 playlist entries or " +
        "16 MiB encoded metadata; at most 100,000 artist credits). Reduce the server library and retry.") : IOException(message)

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
    private var remainingArtists = limits.artistMemberships

    init {
        require(limits.playlists >= 0 && limits.entries >= 0 && limits.encodedMetadataBytes >= 0 && limits.artistMemberships >= 0)
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
        var artistsLeft = remainingArtists
        for (song in songs) {
            val characters = listOf(song.title, song.artist, song.album, song.albumArtist, song.trackNumber)
                .sumOf { it.length.toLong() }
            if (characters > left) throw LibraryResourceLimit()
            val bytes = encodeSong(song).size.toLong()
            if (bytes > left) throw LibraryResourceLimit()
            left -= bytes
            // Splitting credits can amplify one accepted record into many retained group members.
            // Use the exact grouping parser (case-insensitive per-track dedup, blank fallback), and
            // charge every playlist occurrence: an upper bound without a global retained ID map.
            artistCredits(song.artist).forEach {
                if (artistsLeft == 0) throw LibraryResourceLimit()
                artistsLeft--
            }
        }
        entries += songs.size
        remaining = left
        remainingArtists = artistsLeft
    }
}

/** One IO-confined load owns the fresh lists it admits. Keep those admissions through final
 * assembly instead of encoding every fresh record a second time. API-projected track lists are
 * read-only for this load; callers must not mutate them after add(). Retained fallback occurrences
 * are still charged before a result escapes. A failed finish publishes nothing and is not reusable. */
internal class BudgetedLibraryLoad(private val lists:List<TauonPlaylist>,
    limits:LibraryLoadLimits=LIBRARY_LOAD_LIMITS) {
    private val budget=LibraryLoadBudget(lists,limits)
    private val loaded=linkedMapOf<String,List<TauonTrack>>()
    private var finished=false
    fun add(id:String,songs:List<TauonTrack>) {
        check(!finished) {"Library load is already finished"}
        require(lists.any {it.id==id} && !loaded.containsKey(id)) {"Library batch is unknown or already admitted"}
        budget.add(songs) // Refused batches commit neither budget counters nor the loaded map.
        loaded[id]=songs
    }
    fun finish(previous:Map<String,List<TauonTrack>>?):LibraryLoad {
        check(!finished) {"Library load is already finished"};finished=true
        val result=combineLoad(lists,loaded,previous)
        for((id,songs) in result.tracks) if(!loaded.containsKey(id)) budget.add(songs)
        return result
    }
}

/** Full convenience path for callers without a pre-admitted load. Ignore unlisted map entries,
 * as combineLoad does; every retained fresh or fallback occurrence is still charged once. */
internal fun combineBudgetedLoad(lists: List<TauonPlaylist>, loaded: Map<String, List<TauonTrack>>,
    previous: Map<String, List<TauonTrack>>?, limits: LibraryLoadLimits = LIBRARY_LOAD_LIMITS): LibraryLoad {
    val load=BudgetedLibraryLoad(lists,limits)
    for(id in lists.map {it.id}.distinct()) loaded[id]?.let {load.add(id,it)}
    return load.finish(previous)
}
