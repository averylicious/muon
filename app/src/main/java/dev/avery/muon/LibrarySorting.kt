package dev.avery.muon

import java.text.CollationKey
import java.text.Collator
import java.util.Locale

/**
 * How the Songs list is ordered (#109). Recently added puts the newest additions to the library on
 * top, so adding a song is liking it, as in a streaming service's liked songs; A–Z is for finding one.
 */
enum class SongOrder(val label: String) { Added("Recently added"), Title("A–Z") }

/** How the Artists list is ordered: the artists you have most of first, or alphabetically. */
enum class ArtistOrder(val label: String) { MostSongs("Most songs"), Name("A–Z") }

internal fun songOrderFrom(stored: String?): SongOrder =
    SongOrder.entries.firstOrNull { it.name == stored } ?: SongOrder.Added

internal fun artistOrderFrom(stored: String?): ArtistOrder =
    ArtistOrder.entries.firstOrNull { it.name == stored } ?: ArtistOrder.MostSongs

/**
 * Alphabetical as a person reads it in their own language: case and accents do not scatter names
 * ("élan" sits among the e's), which plain string comparison would. A collator is not safe to share
 * between threads, so each sort takes its own.
 */
internal fun libraryCollator(locale: Locale = Locale.getDefault()): Collator = Collator.getInstance(locale)

/**
 * The Songs list in [order]. Recently added relies on Tauon numbering tracks from a running counter
 * as they join its library (seen in `t_jellyfin.py`), so a higher number is a newer addition; a full
 * rescan in Tauon renumbers in scan order. A–Z is by title, then artist; blank titles go last. Ties
 * fall back to the track number so the order is the same every time.
 */
internal fun sortSongs(tracks: List<TauonTrack>, order: SongOrder,
    collator: Collator = libraryCollator()): List<TauonTrack> = when (order) {
    SongOrder.Added -> tracks.sortedByDescending { it.id }
    SongOrder.Title -> tracks.map { SortKey(it, it.title, it.artist, collator) }
        .sortedWith(byName<TauonTrack>().thenByDescending { it.item.id }).map { it.item }
}

/**
 * The Artists list in [order]. Most songs puts the artists with the most songs first, then A–Z among
 * equals. The unknown artist, a blank name, goes last in either order: it is not somebody to look for.
 */
internal fun sortArtists(artists: List<LibraryArtist>, order: ArtistOrder,
    collator: Collator = libraryCollator()): List<LibraryArtist> {
    val keyed = artists.map { SortKey(it, it.name, "", collator) }
    val comparator: Comparator<SortKey<LibraryArtist>> = when (order) {
        ArtistOrder.MostSongs -> compareBy<SortKey<LibraryArtist>> { it.blank }
            .thenByDescending { it.item.tracks.size }.then(byName<LibraryArtist>())
        ArtistOrder.Name -> byName<LibraryArtist>()
    }
    return keyed.sortedWith(comparator.thenBy { it.item.key }).map { it.item }
}

/** A name compared by collation key, computed once per item rather than on every comparison. */
private class SortKey<T>(val item: T, name: String, second: String, collator: Collator) {
    val blank = name.isBlank()
    val name: CollationKey = collator.getCollationKey(name.trim())
    val second: CollationKey = collator.getCollationKey(second.trim())
}

/** Named items first and blank ones last, then by name and by the second name. */
private fun <T> byName(): Comparator<SortKey<T>> =
    compareBy<SortKey<T>> { it.blank }.thenBy { it.name }.thenBy { it.second }
