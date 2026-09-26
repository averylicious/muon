package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class SearchCollectionTest {
    private fun songs(n: Int) = List(n) { TauonTrack(it.toLong(), "Song $it", "", "", 1, true, false) }
    private fun artist(name: String, songs: Int) = LibraryArtist("artist:${name.lowercase()}", name, songs(songs))
    private fun album(title: String, artist: String) = LibraryAlbum("album:$title:$artist", title, artist, songs(1))

    @Test fun artistsStartingWithTheQueryComeFirstThenTheBiggest() {
        val artists = listOf(artist("Seven Lions", 3), artist("Illenium", 40), artist("Dillon Francis", 9), artist("Illuminate", 1))
        assertEquals(listOf("Illenium", "Illuminate", "Dillon Francis"), searchArtists(artists, "ill").map { it.name })
    }

    @Test fun artistMatchesAreCappedAndIgnoreBlankQueries() {
        val artists = List(10) { artist("Band $it", it) }
        assertEquals(SEARCH_ARTIST_LIMIT, searchArtists(artists, "band").size)
        assertEquals(emptyList<LibraryArtist>(), searchArtists(artists, "  "))
    }

    @Test fun albumsMatchOnTitleOrArtistWithTitlesStartingWithTheQueryFirst() {
        val albums = listOf(album("Fallen Embers", "ILLENIUM"), album("Ascend", "ILLENIUM"), album("Ascension", "Someone"), album("Other", "X"))
        assertEquals(listOf("Ascend", "Ascension"), searchAlbums(albums, "ascen").map { it.title })
        assertEquals(listOf("Fallen Embers", "Ascend"), searchAlbums(albums, "illenium").map { it.title })
        assertEquals(emptyList<LibraryAlbum>(), searchAlbums(albums, ""))
    }
}
