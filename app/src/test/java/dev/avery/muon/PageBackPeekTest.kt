package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBackPeekTest {
    @Test fun backLeadsToTheArtistAnAlbumWasOpenedFrom() {
        assertEquals(LibraryPage.Artist("a"), libraryBackPage(LibraryPage.Album("x", fromArtist = "a"), fromSearch = false))
        assertEquals(LibraryPage.Artist("a"), libraryBackPage(LibraryPage.Album("x", fromArtist = "a"), fromSearch = true))
    }

    @Test fun otherPagesGoBackToTheTop() {
        assertEquals(LibraryPage.Top, libraryBackPage(LibraryPage.Album("x"), fromSearch = false))
        assertEquals(LibraryPage.Top, libraryBackPage(LibraryPage.Artist("a"), fromSearch = false))
        assertEquals(LibraryPage.Top, libraryBackPage(LibraryPage.Playlist("p"), fromSearch = false))
    }

    /** Back from a page opened from Search leaves the Library tab: no in-Library preview. */
    @Test fun aPageOpenedFromSearchHasNoLibraryParent() {
        assertNull(libraryBackPage(LibraryPage.Album("x"), fromSearch = true))
        assertNull(libraryBackPage(LibraryPage.Top, fromSearch = false))
    }

    /** The guidance's shift: (width / 20) − 8 dp, 56 dp at 1280. */
    @Test fun theShiftFollowsTheGuidance() {
        assertEquals(56f, peekShiftDp(1280f), 0.001f)
        assertEquals(10f, peekShiftDp(360f), 0.001f)
        assertEquals(0f, peekShiftDp(100f), 0.001f)
    }

    @Test fun progressDeceleratesAndStaysInRange() {
        assertEquals(0f, peekEased(0f), 0.001f)
        assertEquals(1f, peekEased(1f), 0.001f)
        assertTrue(peekEased(0.25f) > 0.25f)
    }
}
