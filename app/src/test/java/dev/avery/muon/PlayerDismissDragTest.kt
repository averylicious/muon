package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDismissDragTest {
    @Test fun theSurfaceFollowsTheFingerDown() {
        assertEquals(120f, playerDismissOffset(120f, 2400f), 0f)
        assertEquals(0f, playerDismissOffset(0f, 2400f), 0f)
    }

    @Test fun draggingUpOrBackPastTheStartRestsWhereItBegan() {
        // Pulling the bar up is not a way to do anything here, and reversing a drag past its
        // start puts the surface back rather than lifting it off the top of the screen.
        assertEquals(0f, playerDismissOffset(-300f, 2400f), 0f)
        assertEquals(0f, playerDismissOffset(-1f, 2400f), 0f)
        assertFalse(playerDismissCloses(-300f, 264f))
    }

    @Test fun theSurfaceNeverFallsPastTheScreenItIsLeaving() {
        assertEquals(2400f, playerDismissOffset(9000f, 2400f), 0f)
        // A screen that has not been measured yet cannot be dragged off it.
        assertEquals(0f, playerDismissOffset(500f, 0f), 0f)
    }

    @Test fun closingTakesADeliberatePullPastTheThreshold() {
        assertTrue(playerDismissCloses(264f, 264f))
        assertTrue(playerDismissCloses(900f, 264f))
        // A short drag moved the surface, but does not put the player away.
        assertFalse(playerDismissCloses(263f, 264f))
        assertTrue(playerDismissOffset(263f, 2400f) > 0f)
    }

    @Test fun aDragOnlyPutsAwayThePlayerItBeganOn() {
        // Closed and opened again under a finger that never lifted: the same long drag must not
        // put the new presentation away.
        assertFalse(playerDismissCommits(startedAt = 7, now = 8, drag = 900f, threshold = 264f))
        assertTrue(playerDismissCommits(startedAt = 7, now = 7, drag = 900f, threshold = 264f))
    }

    @Test fun aDragOnTheRightPlayerStillHasToBeLongEnough() {
        assertFalse(playerDismissCommits(startedAt = 3, now = 3, drag = 263f, threshold = 264f))
        assertTrue(playerDismissCommits(startedAt = 3, now = 3, drag = 264f, threshold = 264f))
    }

    @Test fun anOpenPlayerStaysTrulyBlackEdgeToEdge() {
        // At rest there is nothing to separate, so pure black keeps no outline at all.
        assertFalse(playerSheetEdgeShown(dropped = 0f, pureBlack = true))
        assertTrue(playerSheetEdgeShown(dropped = 1f, pureBlack = true))
    }

    @Test fun onlyPureBlackNeedsTheOutline() {
        // Elsewhere the scrim and shadow already separate the player; the outline would be noise.
        assertFalse(playerSheetEdgeShown(dropped = 400f, pureBlack = false))
    }

    @Test fun anOpenPlayerKeepsItsWholeTopInset() {
        assertEquals(0f, playerInsetReclaimed(dropped = 0f, topInset = 120f), 0f)
    }

    @Test fun theInsetIsGivenBackAsTheSheetDropsBelowTheStatusBar() {
        assertEquals(40f, playerInsetReclaimed(dropped = 40f, topInset = 120f), 0f)
        // Past the status bar there is nothing more to give back: content never rises into it.
        assertEquals(120f, playerInsetReclaimed(dropped = 900f, topInset = 120f), 0f)
    }

    @Test fun withNoTopInsetThereIsNothingToGiveBack() {
        // Landscape or a window away from the status bar: content must not rise at all.
        assertEquals(0f, playerInsetReclaimed(dropped = 300f, topInset = 0f), 0f)
        assertEquals(0f, playerInsetReclaimed(dropped = -50f, topInset = 120f), 0f)
    }

    @Test fun anOpeningPlayerIsMountedBeforeItHasMoved() {
        // Opened but still at zero progress: it must be composed now, or nothing could ever
        // measure and animate it in, and it would wait for progress only it can make.
        assertTrue(playerSheetPresent(open = true, onScreen = false))
    }

    @Test fun aClosingPlayerStaysMountedUntilItHasLeft() {
        assertTrue(playerSheetPresent(open = false, onScreen = true))
        // Closed and fully off screen: gone, so nothing invisible is left in the way.
        assertFalse(playerSheetPresent(open = false, onScreen = false))
    }

    @Test fun anUnmeasuredSheetCannotBeMoved() {
        // Travel before the first measurement would divide by nothing; it moves nothing instead.
        assertEquals(0f, playerSheetFraction(offset = 120f, height = 0f), 0f)
        assertEquals(0.25f, playerSheetFraction(offset = 600f, height = 2400f), 1e-6f)
        // Never past fully closed, and never above fully open.
        assertEquals(1f, playerSheetFraction(offset = 9000f, height = 2400f), 0f)
        assertEquals(0f, playerSheetFraction(offset = -40f, height = 2400f), 0f)
    }

    @Test fun theDecisionIsTakenFromTheFingerNotFromTheSurface() {
        // The surface has stopped at the bottom of a short screen long before the threshold, so
        // the two must not be judged by the same number.
        val stopped = playerDismissOffset(900f, 200f)
        assertEquals(200f, stopped, 0f)
        assertFalse(playerDismissCloses(stopped, 264f))
        assertTrue(playerDismissCloses(900f, 264f))
    }
}
