package dev.avery.muon

import java.util.Locale

/** Blank labels stay blank here; the UI supplies its localized unknown-album/artist label. */
internal data class LibraryAlbum(
    val key: String, val title: String, val artist: String, val tracks: List<TauonTrack>,
)

internal data class LibraryArtist(val key: String, val name: String, val tracks: List<TauonTrack>)

private fun canonicalTag(tag: String): String = tag.trim().lowercase(Locale.ROOT)

private fun albumKey(track: TauonTrack): String {
    val title = canonicalTag(track.album)
    val artist = canonicalTag(albumArtistTag(track.albumArtist, track.artist))
    // Length-prefix both fields: a tag containing our separator cannot collide with another pair.
    // A String also works as a saveable Compose lazy-list key without Parcelable machinery.
    return "album:${title.length}:$title:${artist.length}:$artist"
}

/**
 * Group one server's library by album title + album artist, case-insensitively.
 * First appearance determines group order, display spelling and track order. Do not sort by
 * track number: disc metadata is absent and tags such as B2 have no agreed numeric interpretation.
 */
internal fun groupAlbums(tracks: List<TauonTrack>, checkpoint: () -> Unit = {}): List<LibraryAlbum> =
    tracks.asSequence().onEach { checkpoint() }.distinctBy { it.id }.groupBy(::albumKey).map { (key, members) ->
        checkpoint()
        val first = members.first()
        LibraryAlbum(key, first.album.trim(), albumArtistTag(first.albumArtist, first.artist).trim(), members)
    }

/** Semicolons are the only agreed credit separator; ampersands, slashes and commas remain names. */
internal fun artistCredits(artist: String, checkpoint: () -> Unit = {}): Sequence<String> =
    artist.splitToSequence(';').onEach { checkpoint() }
        .map(String::trim).filter(String::isNotEmpty).distinctBy(::canonicalTag).ifEmpty { sequenceOf("") }

/** Track artists drive this view; a compilation's album artist does not replace performer credits. */
internal fun groupArtists(tracks: List<TauonTrack>, checkpoint: () -> Unit = {}): List<LibraryArtist> {
    val names = linkedMapOf<String, String>()
    val members = linkedMapOf<String, MutableList<TauonTrack>>()
    tracks.asSequence().onEach { checkpoint() }.distinctBy { it.id }.forEach { track ->
        artistCredits(track.artist, checkpoint).forEach { name ->
            checkpoint()
            val key = "artist:${canonicalTag(name)}"
            names.putIfAbsent(key, name)
            members.getOrPut(key) { mutableListOf() }.add(track)
        }
    }
    return members.map { (key, items) ->
        checkpoint(); LibraryArtist(key, names.getValue(key), items.toList())
    }
}

/** The keys of the album a track is grouped into and of each artist it is credited to, as grouped above. */
internal fun trackAlbumKey(track: TauonTrack): String = albumKey(track)
internal fun trackArtistKeys(track: TauonTrack): List<String> =
    artistCredits(track.artist).map { "artist:${canonicalTag(it)}" }.toList()
