package dev.avery.muon

/**
 * The initials shown in an artist's avatar: the first Unicode code point of each of the first two
 * whitespace-separated words, uppercased; one word gives one. No word is treated specially — "The
 * Weeknd" is `TW` — and a blank name gives nothing, for which the avatar shows a generic artist icon.
 * The row itself carries the full name, so the initials are decoration, not the label.
 */
internal fun artistInitials(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { word ->
        String(Character.toChars(word.codePointAt(0))).uppercase()
    }

/** The name to show and announce; grouping leaves blank credits blank for the UI to label. */
internal fun artistLabel(name: String): String = name.ifBlank { "Unknown artist" }

/** The line under an artist: how many songs are credited to them in this library. */
internal fun artistSongCount(count: Int): String = "$count ${if (count == 1) "song" else "songs"}"

/**
 * Which of the theme's three tonal container pairs an artist's avatar uses: primary, secondary or
 * tertiary. Derived from the stable grouping key, so an artist keeps the same colour across refreshes.
 */
internal fun artistTone(key: String): Int = Math.floorMod(key.hashCode(), 3)

/** One library snapshot's artists, and the server it was grouped for. */
internal class ArtistGroups(val origin: String, val artists: List<LibraryArtist>)

/**
 * The artists to show for the server now connected. Groups made for another server are never shown,
 * even for the moment before the new server's grouping finishes. Within one server, the previous
 * grouping stays until a refresh's replacement arrives, so an open artist does not blink out.
 */
internal fun currentArtists(groups: ArtistGroups?, origin: String?): List<LibraryArtist>? =
    groups?.takeIf { origin != null && it.origin == origin }?.artists

/**
 * What to do with an artist selection that outlived the composition that made it, on the same terms
 * as a stored playlist: kept for the server it was chosen on, waited on while that server's artists are
 * still being grouped, and forgotten if the server changed or a refresh removed the artist.
 */
internal fun storedArtist(savedOrigin: String?, savedKey: String?, origin: String?,
    artists: List<LibraryArtist>?): StoredSelection = when {
    savedKey == null || savedOrigin == null -> StoredSelection.None
    origin == null -> StoredSelection.Wait
    savedOrigin != origin -> StoredSelection.Discard
    artists == null -> StoredSelection.Wait
    artists.none { it.key == savedKey } -> StoredSelection.Discard
    else -> StoredSelection.Open
}
