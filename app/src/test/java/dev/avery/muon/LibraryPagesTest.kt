package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LibraryPagesTest {
    @Test fun anOpenPlaylistTakesPrecedenceAsItAlwaysHas() {
        assertEquals(LibraryPage.Playlist("3"), libraryPage("3", artistPage = true, artistKey = "artist:a"))
        assertEquals(LibraryPage.Playlist("3"), libraryPage("3", artistPage = false, artistKey = null))
    }

    @Test fun anArtistPageShowsWhileOpenOrWaitingAndOtherwiseTheLibrary() {
        assertEquals(LibraryPage.Artist("artist:a"), libraryPage(null, artistPage = true, artistKey = "artist:a"))
        // A saved key whose page is not shown (discarded, or disconnected) is the library.
        assertEquals(LibraryPage.Top, libraryPage(null, artistPage = false, artistKey = "artist:a"))
        assertEquals(LibraryPage.Top, libraryPage(null, artistPage = true, artistKey = null))
        assertEquals(LibraryPage.Top, libraryPage(null, artistPage = false, artistKey = null))
    }

    @Test fun aPageIsItsIdentitySoARefreshDoesNotMakeItANewPage() {
        // The artist's songs or the playlist's count changing is the same page, and replays nothing.
        assertEquals(LibraryPage.Artist("artist:a"), LibraryPage.Artist("artist:a"))
        assertEquals(LibraryPage.Artist("artist:a").key, LibraryPage.Artist("artist:a").key)
        // A playlist and an artist never share a key, even when their identifiers are equal.
        assertNotEquals(LibraryPage.Playlist("a").key, LibraryPage.Artist("a").key)
        assertNotEquals(LibraryPage.Top.key, LibraryPage.Playlist("top").key)
        assertNotEquals(LibraryPage.Artist("artist:a").key, LibraryPage.Artist("artist:b").key)
    }

    @Test fun openingADetailGoesForwardAndLeavingItGoesBack() {
        val artist = LibraryPage.Artist("artist:a")
        val playlist = LibraryPage.Playlist("3")
        assertEquals(LibraryMotion.Forward, libraryMotion(LibraryPage.Top, artist))
        assertEquals(LibraryMotion.Forward, libraryMotion(LibraryPage.Top, playlist))
        assertEquals(LibraryMotion.Back, libraryMotion(artist, LibraryPage.Top))
        assertEquals(LibraryMotion.Back, libraryMotion(playlist, LibraryPage.Top))
    }

    @Test fun pagesAtTheSameDepthOnlyCrossFade() {
        assertEquals(LibraryMotion.Across, libraryMotion(LibraryPage.Artist("artist:a"), LibraryPage.Playlist("3")))
        assertEquals(LibraryMotion.Across, libraryMotion(LibraryPage.Artist("artist:a"), LibraryPage.Artist("artist:b")))
        assertEquals(LibraryMotion.Across, libraryMotion(LibraryPage.Top, LibraryPage.Top))
    }

    @Test fun aQuickReversalGoesBackTheWayItCame() {
        val artist = LibraryPage.Artist("artist:a")
        // Back pressed during the opening: the reverse of the forward motion.
        assertEquals(LibraryMotion.Forward, libraryMotion(LibraryPage.Top, artist))
        assertEquals(LibraryMotion.Back, libraryMotion(artist, LibraryPage.Top))
    }

    @Test fun theSlideIsAFixedShortDistanceInTheContainersDirection() {
        assertEquals(84, pageShift(1080, 84))
        assertEquals(-84, pageShift(-1080, 84))
        // Never further than a whole page, and nothing for an unmeasured one.
        assertEquals(40, pageShift(40, 84))
        assertEquals(-40, pageShift(-40, 84))
        assertEquals(0, pageShift(0, 84))
    }
}
