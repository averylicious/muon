package dev.avery.muon

/**
 * The initials shown in an artist's avatar: the first Unicode code point of each of the first two
 * whitespace-separated words, uppercased; one word gives one. No word is treated specially — "The
 * Weeknd" is `TW` — and a blank name gives nothing, for which the avatar shows a generic artist icon.
 * The row itself carries the full name, so the initials are decoration, not the label.
 *
 * Words are split on the same whitespace [String.isBlank] recognises (`Character.isWhitespace` or
 * `Character.isSpaceChar`), including no-break and ideographic spaces. Each initial stays one code point: the
 * uppercase mapping is the simple, locale-independent one, so `ß` does not become `SS`.
 */
internal fun artistInitials(name: String): String = buildString {
    var words = 0
    var inWord = false
    var i = 0
    while (i < name.length && words < 2) {
        val point = name.codePointAt(i)
        // Read by whole code point, so a supplementary letter is never split.
        val space = Character.isWhitespace(point) || Character.isSpaceChar(point)
        if (!space && !inWord) { appendCodePoint(Character.toUpperCase(point)); words++ }
        inWord = !space
        i += Character.charCount(point)
    }
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

/**
 * One library snapshot, compared by identity rather than contents.
 *
 * It is both the key that restarts grouping and the test for whether finished groups still apply,
 * so the two can never disagree: a new snapshot always regroups, and groups are used only for the
 * snapshot they were made from. Comparing contents instead would be wrong both ways — a key compared
 * by contents does not restart for an equal new list, which an identity guard would then reject
 * forever — and it would cost a pass over the whole library on every composition.
 */
internal class LibrarySnapshot(val tracks: List<TauonTrack>) {
    override fun equals(other: Any?): Boolean = other is LibrarySnapshot && other.tracks === tracks
    override fun hashCode(): Int = System.identityHashCode(tracks)
}

/** One snapshot's artists, and the server it was grouped for. */
internal class ArtistGroups(val origin: String, val snapshot: LibrarySnapshot, val artists: List<LibraryArtist>)

/**
 * The artists to show and act on for the library now loaded, or null while they are still being
 * grouped. Groups are used only for the snapshot and server they were made from, compared exactly as
 * the grouping effect compares its keys: after a refresh, a disconnect or a server change, nothing from
 * the previous library is offered, even for the moment before the new grouping finishes.
 */
internal fun currentArtists(groups: ArtistGroups?, origin: String?, snapshot: LibrarySnapshot): List<LibraryArtist>? =
    groups?.takeIf { origin != null && it.origin == origin && it.snapshot == snapshot }?.artists

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

/**
 * Whether the artist page is showing. A saved artist whose current grouping is still on its way keeps
 * its page, waiting, rather than dropping back to the list: Back stays able to close it, so it
 * cannot reappear by itself once the grouping arrives.
 */
internal fun artistPageShown(selection: StoredSelection, connected: Boolean): Boolean =
    connected && (selection == StoredSelection.Open || selection == StoredSelection.Wait)
