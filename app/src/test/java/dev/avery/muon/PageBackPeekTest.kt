package dev.avery.muon

import androidx.activity.BackEventCompat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Back from a page opened from Search leaves the Library tab: there is no Library page to show underneath. */
    @Test fun aPageOpenedFromSearchHasNoLibraryParent() {
        assertNull(libraryBackPage(LibraryPage.Album("x"), fromSearch = true))
        assertNull(libraryBackPage(LibraryPage.Artist("a"), fromSearch = true))
        assertNull(libraryBackPage(LibraryPage.Top, fromSearch = false))
    }

    /** It still previews, as a card on its own; every other page has a parent, and the top has no Back at all. */
    @Test fun onlyAPageWithNoLibraryParentLeavesForSearch() {
        assertTrue(libraryBackLeavesForSearch(LibraryPage.Album("x"), fromSearch = true))
        assertTrue(libraryBackLeavesForSearch(LibraryPage.Artist("a"), fromSearch = true))
        assertTrue(libraryBackLeavesForSearch(LibraryPage.Playlist("p"), fromSearch = true))
        // An album opened from an artist's page goes back to that page first, wherever the artist came from.
        assertFalse(libraryBackLeavesForSearch(LibraryPage.Album("x", fromArtist = "a"), fromSearch = true))
        assertFalse(libraryBackLeavesForSearch(LibraryPage.Album("x"), fromSearch = false))
        assertFalse(libraryBackLeavesForSearch(LibraryPage.Top, fromSearch = true))
        assertFalse(libraryBackLeavesForSearch(LibraryPage.Top, fromSearch = false))
    }

    /** The guidance's shift: (width / 20) − 8 dp, 56 dp at 1280. */
    @Test fun theShiftFollowsTheGuidance() {
        assertEquals(56f, peekShiftDp(1280f), 0.001f)
        assertEquals(10f, peekShiftDp(360f), 0.001f)
        assertEquals(0f, peekShiftDp(100f), 0.001f)
    }

    /** Up or down with the finger, decelerating, over half the height at most. */
    @Test fun theSurfaceFollowsTheFingerLessAndLess() {
        assertEquals(0f, backFollow(0f, 2000f), 0f)
        assertEquals(0.75f, backFollow(500f, 2000f), 1e-6f)
        assertEquals(-0.75f, backFollow(-500f, 2000f), 1e-6f)
        assertEquals(1f, backFollow(1000f, 2000f), 0f)
        assertEquals(1f, backFollow(5000f, 2000f), 0f)
        assertEquals(-1f, backFollow(-5000f, 2000f), 0f)
    }

    /** A button's Back reports no touch position, and a surface not yet measured has no height. */
    @Test fun aBackWithNoFingerFollowsNothing() {
        assertEquals(0f, backFollow(Float.NaN, 2000f), 0f)
        assertEquals(0f, backFollow(300f, 0f), 0f)
        assertEquals(0f, backFollow(300f, Float.NaN), 0f)
    }

    /** The vertical shift keeps the guidance's 8 dp margin: (height / 20) − 8 dp at most, 32 dp at 800. */
    @Test fun theLiftStaysWithinTheCardsMargin() {
        assertEquals(32f, peekLiftDp(800f, follow = 1f, eased = 1f), 0.001f)
        assertEquals(-32f, peekLiftDp(800f, follow = -1f, eased = 1f), 0.001f)
        assertEquals(32f, peekLiftDp(800f, follow = 3f, eased = 2f), 0.001f)
        assertEquals(8f, peekLiftDp(800f, follow = 0.5f, eased = 0.5f), 0.001f)
        // At rest, and once a cancelled gesture has settled, the card is where it belongs.
        assertEquals(0f, peekLiftDp(800f, follow = 1f, eased = 0f), 0f)
        assertEquals(0f, peekLiftDp(800f, follow = 0f, eased = 1f), 0f)
        assertEquals(0f, peekLiftDp(100f, follow = 1f, eased = 1f), 0f)
    }

    @Test fun progressDeceleratesAndStaysInRange() {
        assertEquals(0f, peekEased(0f), 0.001f)
        assertEquals(1f, peekEased(1f), 0.001f)
        assertTrue(peekEased(0.25f) > 0.25f)
    }

    /** A button or hardware Back completes with no progress: no card, so the page's own transition plays. */
    @Test fun aBackWithNoProgressShowsNoCard() = runTest {
        val peek = BackPeek()
        val gesture = peek.begin()
        assertFalse(peek.active)
        assertTrue(peek.owns(gesture))
    }

    /** What is underneath is put in place once, after the card is on, and the card then follows the finger. */
    @Test fun theCardAppearsWithTheFirstProgressEvent() = runTest {
        val peek = BackPeek()
        peek.begin()
        val activeWhenShown = mutableListOf<Boolean>()
        peek.follow(BackEventCompat(0f, 400f, 0.2f, BackEventCompat.EDGE_RIGHT)) { activeWhenShown += peek.active }
        peek.follow(BackEventCompat(30f, 340f, 0.6f, BackEventCompat.EDGE_RIGHT)) { activeWhenShown += peek.active }
        assertEquals(listOf(true), activeWhenShown)
        assertTrue(peek.active)
        assertEquals(0.6f, peek.progress.value, 0f)
        assertEquals(BackEventCompat.EDGE_RIGHT, peek.edge)
        // Measured from the gesture's first event, and up is negative.
        assertEquals(-60f, peek.drag, 0f)
    }

    /** A second Back over a cancelled card still settling finishes as that card, even with no progress of its own. */
    @Test fun aGestureTakingOverASettlingCardCarriesOnFromIt() = runTest {
        val peek = BackPeek()
        peek.begin()
        peek.follow(BackEventCompat(0f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
        peek.begin()
        assertTrue(peek.active)
        // If it does send progress, what is underneath is put in place again and the finger is measured afresh.
        var shown = 0
        peek.follow(BackEventCompat(0f, 900f, 0.1f, BackEventCompat.EDGE_LEFT)) { shown++ }
        assertEquals(1, shown)
        assertEquals(0f, peek.drag, 0f)
        assertEquals(0.1f, peek.progress.value, 0f)
    }

    /** A logical navigation during a held gesture puts the card away, and the rest of that gesture leaves it away. */
    @Test fun aCardPutAwayUnderAGestureStaysAway() = runTest {
        val peek = BackPeek()
        peek.begin()
        var shown = 0
        peek.follow(BackEventCompat(0f, 400f, 0.3f, BackEventCompat.EDGE_LEFT)) { shown++ }
        peek.interrupt()
        peek.reset()
        peek.follow(BackEventCompat(0f, 400f, 0.4f, BackEventCompat.EDGE_LEFT)) { shown++ }
        assertFalse(peek.active)
        assertEquals(1, shown)
    }

    @Test fun aSettledCardIsFullyPutBack() = runTest {
        val peek = BackPeek()
        peek.begin(alone = true)
        peek.follow(BackEventCompat(0f, 400f, 0.2f, BackEventCompat.EDGE_LEFT))
        peek.follow(BackEventCompat(0f, 450f, 0.5f, BackEventCompat.EDGE_LEFT))
        assertTrue(peek.alone)
        peek.reset()
        assertFalse(peek.active)
        assertFalse(peek.alone)
        assertEquals(0f, peek.progress.value, 0f)
        assertEquals(0f, peek.drag, 0f)
    }
}
