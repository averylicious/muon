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

    @Test fun theDecisionIsTakenFromTheFingerNotFromTheSurface() {
        // The surface has stopped at the bottom of a short screen long before the threshold, so
        // the two must not be judged by the same number.
        val stopped = playerDismissOffset(900f, 200f)
        assertEquals(200f, stopped, 0f)
        assertFalse(playerDismissCloses(stopped, 264f))
        assertTrue(playerDismissCloses(900f, 264f))
    }
}
