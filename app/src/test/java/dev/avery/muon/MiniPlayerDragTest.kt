package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerDragTest {
    @Test fun aLongDragCannotOpenAfterEligibilityIsLost() {
        assertTrue(miniDragOpens(-400f, 132f, eligible = true))
        // The same completed distance must not open outgoing/empty/disconnected content.
        assertFalse(miniDragOpens(-400f, 132f, eligible = false))
    }

    @Test fun pullingDownDoesNothingAtAll() {
        assertEquals(0f, miniDragOffset(200f, 66f), 0f)
        assertEquals(0f, miniDragOffset(0f, 66f), 0f)
        // However hard it is pulled down, it is not a way to open the player.
        assertFalse(miniDragOpens(400f, 132f))
    }

    @Test fun theLiftFollowsTheFingerButGivesLessThanItIsAsked() {
        assertEquals(-30f / MINI_DRAG_RESISTANCE, miniDragOffset(-30f, 66f), 1e-6f)
        assertTrue(miniDragOffset(-30f, 66f) > -30f)
    }

    @Test fun theLiftStopsRatherThanOpeningAHoleInTheChrome() {
        assertEquals(-66f, miniDragOffset(-1000f, 66f), 0f)
        assertEquals(-66f, miniDragOffset(-66f * MINI_DRAG_RESISTANCE, 66f), 1e-4f)
    }

    @Test fun openingTakesADeliberatePullPastTheThreshold() {
        assertTrue(miniDragOpens(-132f, 132f))
        assertTrue(miniDragOpens(-400f, 132f))
        // A short pull settles back instead, even though the surface did move for it.
        assertFalse(miniDragOpens(-131f, 132f))
        assertTrue(miniDragOffset(-131f, 66f) < 0f)
    }

    @Test fun theDecisionIsTakenFromTheFingerNotFromTheSurface() {
        // The surface has stopped at its limit well before the pull is long enough to open,
        // so the two must not be judged by the same number.
        val capped = miniDragOffset(-200f, 24f)
        assertEquals(-24f, capped, 0f)
        assertFalse(miniDragOpens(capped, 132f))
        assertTrue(miniDragOpens(-200f, 132f))
    }
}
