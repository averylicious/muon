package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class ArtistPageTest {
    private fun track(id: Long, artist: String = "A") = TauonTrack(id, "Song $id", artist, "Album", 200_000, true, false)
    private fun album(title: String, vararg tracks: TauonTrack) = LibraryAlbum("album:$title", title, "X", tracks.toList())

    @Test fun theArtistsAlbumsAreThoseHoldingTheirSongsNewestFirst() {
        val mine = listOf(track(1), track(5), track(9))
        val artist = LibraryArtist("artist:a", "A", mine)
        val albums = listOf(
            album("Debut", mine[0], track(2, "B")),
            album("Someone else's", track(3, "B"), track(4, "B")),
            album("Guest spot", track(8, "C"), mine[2]),
            album("Second", mine[1]),
        )
        assertEquals(listOf("Guest spot", "Second", "Debut"), artistAlbums(artist, albums).map { it.title })
    }

    @Test fun anArtistWithNoAlbumsHasAnEmptyRow() {
        assertEquals(emptyList<LibraryAlbum>(), artistAlbums(LibraryArtist("artist:a", "A", listOf(track(1))), emptyList()))
    }

    @Test fun theSummaryCountsAlbumsThenSongs() {
        assertEquals("1 album, 14 songs", artistSummary(1, 14))
        assertEquals("3 albums, 1 song", artistSummary(3, 1))
        assertEquals("2 songs", artistSummary(0, 2))
    }

    @Test fun anAlbumOpenedFromAnArtistIsALevelBelowIt() {
        val artist = libraryPage(null, true, "artist:a")
        val album = libraryPage(null, true, "artist:a", albumPage = true, albumKey = "album:x")
        assertEquals(LibraryPage.Album("album:x", fromArtist = "artist:a"), album)
        assertEquals(LibraryMotion.Forward, libraryMotion(artist, album))
        assertEquals(LibraryMotion.Back, libraryMotion(album, artist))
        // The same album from the Albums grid is one level down, and keeps its page identity.
        val fromGrid = libraryPage(null, false, null, albumPage = true, albumKey = "album:x")
        assertEquals(LibraryPage.Album("album:x"), fromGrid)
        assertEquals(album.key, fromGrid.key)
        assertEquals(LibraryMotion.Back, libraryMotion(fromGrid, LibraryPage.Top))
    }
}
