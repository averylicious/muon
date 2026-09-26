package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackTargetTest {
    @Test fun screensTakeBackInTheOrderTheyAreStackedIn() {
        // Everything open at once, peeled off one press at a time.
        assertEquals(BackTarget.Lyrics, backTarget(connected = true, lyricsShown = true,
            overlayOpen = true, onLibraryTab = false, playlistOpen = true))
        assertEquals(BackTarget.Player, backTarget(connected = true, lyricsShown = false,
            overlayOpen = true, onLibraryTab = false, playlistOpen = true))
        assertEquals(BackTarget.Tab, backTarget(connected = true, lyricsShown = false,
            overlayOpen = false, onLibraryTab = false, playlistOpen = true))
        assertEquals(BackTarget.Playlist, backTarget(connected = true, lyricsShown = false,
            overlayOpen = false, onLibraryTab = true, playlistOpen = true))
    }

    @Test fun theQueueClosesBeforeThePlayerItCoversAndAHeldGestureStillActs() {
        assertEquals(BackTarget.Queue, backTarget(connected = true, lyricsShown = false,
            overlayOpen = true, onLibraryTab = false, playlistOpen = true, queueShown = true))
        assertEquals(BackTarget.Player, backTarget(connected = true, lyricsShown = false,
            overlayOpen = true, onLibraryTab = true, playlistOpen = false, queueShown = false))
        assertTrue(playerGestureCommits(BackTarget.Queue))
    }

    @Test fun theLibraryItselfLeavesBackToTheSystem() {
        assertEquals(BackTarget.None, backTarget(connected = true, lyricsShown = false,
            overlayOpen = false, onLibraryTab = true, playlistOpen = false))
        // Nothing is on screen to go back through until a server is connected.
        assertEquals(BackTarget.None, backTarget(connected = false, lyricsShown = true,
            overlayOpen = true, onLibraryTab = false, playlistOpen = true))
    }

    @Test fun aHeldGestureActsWhileThePlayerIsStillUnderIt() {
        assertTrue(playerGestureCommits(BackTarget.Player))
        // Lyrics opened over the player while the finger was down: still the player's own stack,
        // so letting go closes Lyrics rather than the player it was drawn over.
        assertTrue(playerGestureCommits(BackTarget.Lyrics))
    }

    @Test fun aHeldGestureWhosePlayerWentAwayNavigatesNothing() {
        // Closed by its own collapse button, or by the queue emptying, mid-gesture. Letting go
        // must not send the user back a tab or out of a playlist they never aimed at.
        assertFalse(playerGestureCommits(BackTarget.Tab))
        assertFalse(playerGestureCommits(BackTarget.Playlist))
        assertFalse(playerGestureCommits(BackTarget.None))
    }
}
