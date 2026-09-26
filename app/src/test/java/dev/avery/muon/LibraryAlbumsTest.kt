package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LibraryAlbumsTest {
    private val english = libraryCollator(Locale.ENGLISH)
    private fun track(id: Long, number: String = "", duration: Long = 200_000) =
        TauonTrack(id, "Song $id", "A", "Album", duration, true, false, trackNumber = number)
    private fun album(title: String, artist: String, vararg ids: Long) =
        LibraryAlbum("album:${title.lowercase()}:${artist.lowercase()}", title, artist, ids.map { track(it) })

    @Test fun recentlyAddedPutsTheAlbumWithTheNewestSongFirst() {
        val albums = listOf(album("Old", "A", 1, 2), album("New", "B", 3, 90), album("Middle", "C", 50))
        assertEquals(listOf("New", "Middle", "Old"), sortAlbums(albums, AlbumOrder.Added, english).map { it.title })
    }

    @Test fun alphabeticalIgnoresCaseAndPutsUntitledLast() {
        val albums = listOf(album("", "Z", 1), album("stories", "Avicii", 2), album("Ascend", "ILLENIUM", 3), album("élan", "E", 4))
        assertEquals(listOf("Ascend", "élan", "stories", ""), sortAlbums(albums, AlbumOrder.Title, english).map { it.title })
    }

    @Test fun theSummaryReadsLikeTheQueues() {
        assertEquals("2 songs, 7 minutes", albumSummary(listOf(track(1, duration = 200_000), track(2, duration = 220_000))))
        assertEquals("2 songs, at least 3 minutes", albumSummary(listOf(track(1, duration = 200_000), track(2, duration = 0))))
    }

    @Test fun anUntitledAlbumHasALabel() {
        assertEquals("Unknown album", albumLabel(" "))
        assertEquals("Stories", albumLabel("Stories"))
    }

    @Test fun storedOrdersAndSelectionsFollowTheArtistRules() {
        assertEquals(AlbumOrder.Added, albumOrderFrom(null))
        assertEquals(AlbumOrder.Title, albumOrderFrom("Title"))
        val albums = listOf(album("Stories", "Avicii", 1))
        val key = albums[0].key
        assertEquals(StoredSelection.None, storedAlbum(null, null, "http://a", albums))
        assertEquals(StoredSelection.Wait, storedAlbum("http://a", key, null, null))
        assertEquals(StoredSelection.Wait, storedAlbum("http://a", key, "http://a", null))
        assertEquals(StoredSelection.Discard, storedAlbum("http://a", key, "http://b", albums))
        assertEquals(StoredSelection.Discard, storedAlbum("http://a", "album:gone", "http://a", albums))
        assertEquals(StoredSelection.Open, storedAlbum("http://a", key, "http://a", albums))
    }

    @Test fun albumsAreShownOnlyForTheirOwnSnapshotAndServer() {
        val snapshot = LibrarySnapshot(listOf(track(1)))
        val groups = AlbumGroups("http://a", snapshot, groupAlbums(snapshot.tracks))
        assertNotNull(currentAlbums(groups, "http://a", snapshot))
        assertNull(currentAlbums(groups, "http://b", snapshot))
        assertNull(currentAlbums(groups, "http://a", LibrarySnapshot(snapshot.tracks.toList())))
    }

    @Test fun anOpenAlbumIsItsOwnPage() {
        assertEquals(LibraryPage.Album("album:x"), libraryPage(null, false, null, albumPage = true, albumKey = "album:x"))
        // A playlist still takes precedence, as it always has.
        assertEquals(LibraryPage.Playlist("3"), libraryPage("3", false, null, albumPage = true, albumKey = "album:x"))
        assertNotEquals(LibraryPage.Album("a").key, LibraryPage.Artist("a").key)
        assertEquals(LibraryMotion.Forward, libraryMotion(LibraryPage.Top, LibraryPage.Album("album:x")))
    }
}
