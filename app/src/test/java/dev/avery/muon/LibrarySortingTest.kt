package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LibrarySortingTest {
    private val english = libraryCollator(Locale.ENGLISH)
    private fun track(id: Long, title: String, artist: String = "A") =
        TauonTrack(id, title, artist, "Album", 1000, true, false)
    private fun artist(name: String, songs: Int) =
        LibraryArtist("artist:${name.lowercase()}", name, (1..songs).map { track(it.toLong(), "t$it", name) })
    private fun titles(tracks: List<TauonTrack>) = tracks.map { it.title }

    @Test fun recentlyAddedPutsTheNewestAdditionsOnTop() {
        val tracks = listOf(track(3, "c"), track(10, "a"), track(1, "b"))
        assertEquals(listOf(10L, 3L, 1L), sortSongs(tracks, SongOrder.Added, english).map { it.id })
    }

    @Test fun alphabeticalIgnoresCaseAndKeepsAccentsWithTheirLetter() {
        val tracks = listOf(track(1, "fable"), track(2, "Echo"), track(3, "élan"), track(4, "apple"), track(5, "Banana"))
        assertEquals(listOf("apple", "Banana", "Echo", "élan", "fable"),
            titles(sortSongs(tracks, SongOrder.Title, english)))
    }

    @Test fun equalTitlesAreOrderedByArtistThenNewestFirst() {
        val tracks = listOf(track(1, "Intro", "Zed"), track(2, "Intro", "Abba"), track(3, "Intro", "Abba"))
        assertEquals(listOf(3L, 2L, 1L), sortSongs(tracks, SongOrder.Title, english).map { it.id })
    }

    @Test fun blankTitlesGoLastAndSortingNeverChangesTheInput() {
        val tracks = listOf(track(1, " "), track(2, "b"), track(3, "a"))
        val copy = tracks.toList()
        assertEquals(listOf("a", "b", " "), titles(sortSongs(tracks, SongOrder.Title, english)))
        assertEquals(copy, tracks)
    }

    @Test fun mostSongsFirstThenAlphabeticalWithTheUnknownArtistLast() {
        val artists = listOf(artist("", 50), artist("beta", 2), artist("Alpha", 2), artist("Vicetone", 42))
        assertEquals(listOf("Vicetone", "Alpha", "beta", ""),
            sortArtists(artists, ArtistOrder.MostSongs, english).map { it.name })
    }

    @Test fun artistsAlphabeticallyWithTheUnknownArtistLast() {
        val artists = listOf(artist("", 1), artist("Vicetone", 42), artist("bôa", 1), artist("BEAUZ", 2))
        assertEquals(listOf("BEAUZ", "bôa", "Vicetone", ""),
            sortArtists(artists, ArtistOrder.Name, english).map { it.name })
    }

    @Test fun storedOrdersFallBackToTheDefaults() {
        assertEquals(SongOrder.Added, songOrderFrom(null))
        assertEquals(SongOrder.Added, songOrderFrom("Shuffle"))
        assertEquals(SongOrder.Title, songOrderFrom("Title"))
        assertEquals(ArtistOrder.MostSongs, artistOrderFrom(null))
        assertEquals(ArtistOrder.Name, artistOrderFrom("Name"))
    }
}
