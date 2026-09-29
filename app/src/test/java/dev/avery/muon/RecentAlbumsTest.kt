package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentAlbumsTest {
    @Test fun aPlayedAlbumMovesToTheFrontOnce() {
        assertEquals(listOf("b", "a", "c"), playedAlbum(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("d", "a", "b"), playedAlbum(listOf("a", "b"), "d"))
    }

    @Test fun onlyTheMostRecentAreKept() {
        val full = (1..RECENT_ALBUMS).map { "$it" }
        val next = playedAlbum(full, "new")
        assertEquals(RECENT_ALBUMS, next.size)
        assertEquals("new", next.first())
        assertEquals("${RECENT_ALBUMS - 1}", next.last())
    }

    @Test fun theTrackComesFromThisServersMediaId() {
        assertEquals(42L, trackIdOf("http://10.0.0.2:7814/42", "http://10.0.0.2:7814"))
        assertNull(trackIdOf("http://10.0.0.3:7814/42", "http://10.0.0.2:7814"))
        assertNull(trackIdOf("http://10.0.0.2:7814/x", "http://10.0.0.2:7814"))
        assertNull(trackIdOf(null, "http://10.0.0.2:7814"))
        assertNull(trackIdOf("http://10.0.0.2:7814/42", null))
    }

    @Test fun albumsNoLongerInTheLibraryAreSkipped() {
        val a = LibraryAlbum("a", "A", "", emptyList())
        val c = LibraryAlbum("c", "C", "", emptyList())
        assertEquals(listOf(c, a), recentAlbums(listOf("c", "gone", "a"), listOf(a, c)))
    }
}
