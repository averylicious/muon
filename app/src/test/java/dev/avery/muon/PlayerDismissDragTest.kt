package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDismissDragTest {
    @Test fun theSheetFollowsTheFingerDown() {
        assertEquals(0.05f, playerSheetDragged(baseline = 0f, travel = 120f, height = 2400f), 1e-6f)
        assertEquals(0f, playerSheetDragged(baseline = 0f, travel = 0f, height = 2400f), 0f)
    }

    @Test fun draggingUpOrBackPastTheStartRestsWhereItBegan() {
        // Pulling an open player's bar up is not a way to do anything here, and reversing a drag
        // past its start puts the sheet back rather than lifting it off the top of the screen.
        assertEquals(0f, playerSheetDragged(baseline = 0f, travel = -300f, height = 2400f), 0f)
        assertEquals(0f, playerSheetDragged(baseline = 0f, travel = -1f, height = 2400f), 0f)
        assertFalse(playerDismissCloses(-300f, 264f))
    }

    @Test fun theSheetNeverFallsPastClosed() {
        assertEquals(1f, playerSheetDragged(baseline = 0f, travel = 9000f, height = 2400f), 0f)
    }

    @Test fun closingTakesADeliberatePullPastTheThreshold() {
        assertTrue(playerDismissCloses(264f, 264f))
        assertTrue(playerDismissCloses(900f, 264f))
        // A short drag moved the surface, but does not put the player away.
        assertFalse(playerDismissCloses(263f, 264f))
        assertTrue(playerSheetDragged(baseline = 0f, travel = 263f, height = 2400f) > 0f)
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

    @Test fun anUnmeasuredSheetStaysWhereItWas() {
        // Travel before the first measurement would divide by nothing; the sheet stays put instead,
        // including one grabbed part-way.
        assertEquals(0f, playerSheetDragged(baseline = 0f, travel = 500f, height = 0f), 0f)
        assertEquals(0.6f, playerSheetDragged(baseline = 0.6f, travel = 500f, height = 0f), 0f)
    }

    @Test fun grabbingASheetMidOpeningHoldsItWhereItIs() {
        // Taken over 60% of the way from open, before the finger has moved: it stays there,
        // rather than snapping to the top as if the drag had begun from rest.
        assertEquals(0.6f, playerSheetDragged(baseline = 0.6f, travel = 0f, height = 2400f), 0f)
        assertEquals(0.7f, playerSheetDragged(baseline = 0.6f, travel = 240f, height = 2400f), 1e-6f)
        // From there the finger can carry it back up towards open, but not beyond.
        assertEquals(0.35f, playerSheetDragged(baseline = 0.6f, travel = -600f, height = 2400f), 1e-6f)
        assertEquals(0f, playerSheetDragged(baseline = 0.6f, travel = -9000f, height = 2400f), 0f)
    }

    @Test fun whereTheSheetAlreadyWasDoesNotCountTowardsClosing() {
        // Grabbed nearly closed and moved a little: the sheet is far down, but the finger has not
        // pulled the threshold, so letting go must not put the player away.
        assertTrue(playerSheetDragged(baseline = 0.9f, travel = 40f, height = 2400f) > 0.9f)
        assertFalse(playerDismissCommits(startedAt = 1, now = 1, drag = 40f, threshold = 252f))
        assertTrue(playerDismissCommits(startedAt = 1, now = 1, drag = 252f, threshold = 252f))
    }

    @Test fun theGrabberMovesExactlyWithTheFinger() {
        // The edge is drawn lower by the inset the content gives back, so the content's own
        // displacement — edge minus what it rose by — equals the finger's at every point.
        listOf(0f, 1f, 30f, 119f, 120f, 121f, 600f).forEach { finger ->
            val content = playerSheetEdgeDrop(finger, topInset = 120f) - playerInsetReclaimed(finger, 120f)
            assertEquals(finger, content, 0f)
        }
    }

    @Test fun theEdgeClosesTheInsetGapAboveTheGrabber() {
        // At rest nothing moves; once the sheet is a whole inset down, the edge sits that much
        // lower than the finger's displacement, which is the blank space it no longer shows.
        assertEquals(0f, playerSheetEdgeDrop(0f, topInset = 120f), 0f)
        assertEquals(60f, playerSheetEdgeDrop(30f, topInset = 120f), 0f)
        assertEquals(720f, playerSheetEdgeDrop(600f, topInset = 120f), 0f)
        // With no inset to give back, the edge simply follows the finger.
        assertEquals(600f, playerSheetEdgeDrop(600f, topInset = 0f), 0f)
    }
}
