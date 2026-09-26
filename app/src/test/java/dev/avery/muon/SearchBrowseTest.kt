package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class SearchBrowseTest {
    private fun songs(vararg ids: Long) = ids.map { TauonTrack(it, "Song $it", "", "", 1, true, false) }
    private fun artist(name: String, count: Int) = LibraryArtist("artist:${name.lowercase()}", name, songs(*LongArray(count) { it.toLong() }))
    private fun album(title: String, vararg ids: Long) = LibraryAlbum("album:$title", title, "X", songs(*ids))

    @Test fun theBrowseLeadsWithTheArtistsWithTheMostSongs() {
        val artists = listOf(artist("Small", 2), artist("", 99), artist("Big", 30), artist("Mid", 9))
        assertEquals(listOf("Big", "Mid"), topArtists(artists, count = 2).map { it.name })
    }

    @Test fun theNewestAlbumsComeFirstAndUntitledOnesAreLeftOut() {
        val albums = listOf(album("Old", 1), album("", 99), album("New", 50), album("Mid", 20))
        assertEquals(listOf("New", "Mid", "Old"), newestAlbums(albums).map { it.title })
    }

    @Test fun suggestionsAlternateFromAnArtist() {
        val suggestions = searchSuggestions(listOf(artist("A", 1), artist("B", 1)), listOf(album("X", 1), album("Y", 2), album("Z", 3)))
        assertEquals(listOf("A", "X", "B", "Y", "Z"), suggestions.map {
            when (it) { is SearchSuggestion.Artist -> it.artist.name; is SearchSuggestion.Album -> it.album.title }
        })
    }

    @Test fun anEmptyLibraryHasNothingToSuggest() {
        assertEquals(emptyList<SearchSuggestion>(), searchSuggestions(emptyList(), emptyList()))
    }
}
