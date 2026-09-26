package dev.avery.muon

import java.text.Collator

/** How the Albums grid is ordered: newest additions first, as Songs defaults to, or by title. */
enum class AlbumOrder(val label: String) { Added("Recently added"), Title("A–Z") }

internal fun albumOrderFrom(stored: String?): AlbumOrder =
    AlbumOrder.entries.firstOrNull { it.name == stored } ?: AlbumOrder.Added

/** The name to show for an album; grouping leaves blank titles blank for the UI to label. */
internal fun albumLabel(title: String): String = title.ifBlank { "Unknown album" }

/**
 * The Albums grid in [order]. Recently added puts the album holding the newest track first, using
 * the same Tauon track numbering as Songs. A–Z is by title, then album artist; untitled albums go last.
 */
internal fun sortAlbums(albums: List<LibraryAlbum>, order: AlbumOrder,
    collator: Collator = libraryCollator()): List<LibraryAlbum> = when (order) {
    AlbumOrder.Added -> albums.sortedByDescending { album -> album.tracks.maxOfOrNull { it.id } ?: -1L }
    AlbumOrder.Title -> {
        val keyed = albums.map { Triple(it, collator.getCollationKey(it.title.trim()), collator.getCollationKey(it.artist.trim())) }
        keyed.sortedWith(compareBy<Triple<LibraryAlbum, java.text.CollationKey, java.text.CollationKey>> { it.first.title.isBlank() }
            .thenBy { it.second }.thenBy { it.third }.thenBy { it.first.key }).map { it.first }
    }
}

/** "14 songs, 56 minutes", worded exactly as the queue's summary; unknown lengths make it "at least". */
internal fun albumSummary(tracks: List<TauonTrack>): String =
    queueSummary(tracks.size, queueLength(tracks.map { it.durationMs.takeIf { d -> d > 0 } }))

/** One snapshot's albums and the server they were grouped for; the same terms as [ArtistGroups]. */
internal class AlbumGroups(val origin: String, val snapshot: LibrarySnapshot, val albums: List<LibraryAlbum>)

/** The albums for the library now loaded, or null while grouping; never another snapshot's or server's. */
internal fun currentAlbums(groups: AlbumGroups?, origin: String?, snapshot: LibrarySnapshot): List<LibraryAlbum>? =
    groups?.takeIf { origin != null && it.origin == origin && it.snapshot == snapshot }?.albums

/** A saved album selection, judged exactly as a saved artist is ([storedArtist]). */
internal fun storedAlbum(savedOrigin: String?, savedKey: String?, origin: String?,
    albums: List<LibraryAlbum>?): StoredSelection = when {
    savedKey == null || savedOrigin == null -> StoredSelection.None
    origin == null -> StoredSelection.Wait
    savedOrigin != origin -> StoredSelection.Discard
    albums == null -> StoredSelection.Wait
    albums.none { it.key == savedKey } -> StoredSelection.Discard
    else -> StoredSelection.Open
}
