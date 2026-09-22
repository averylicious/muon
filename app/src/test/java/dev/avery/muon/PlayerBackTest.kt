package dev.avery.muon

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBackTest {
    @Test fun aGestureAtRestDrawsTheOrdinaryPlayer() {
        assertEquals(1f, playerBackScale(0f, 1080f, 132f), 0f)
        assertEquals(0f, playerBackSlide(0f, BackEventCompat.EDGE_LEFT, 66f), 0f)
    }

    @Test fun theShrinkIsAFixedDistanceRatherThanAFraction() {
        assertEquals(1f - 132f / 1080f, playerBackScale(1f, 1080f, 132f), 1e-6f)
        // Twice the width, so half the fraction: a larger screen gives up less of itself.
        assertEquals(1f - 132f / 2160f, playerBackScale(1f, 2160f, 132f), 1e-6f)
        assertEquals(1f - 66f / 1080f, playerBackScale(0.5f, 1080f, 132f), 1e-6f)
    }

    @Test fun anUnmeasuredOrImpossibleSurfaceIsLeftAlone() {
        assertEquals(1f, playerBackScale(1f, Float.NaN, 132f), 0f)
        assertEquals(1f, playerBackScale(1f, 0f, 132f), 0f)
        // Narrower than the shrink distance: it stops at nothing instead of turning inside out.
        assertEquals(0f, playerBackScale(1f, 100f, 132f), 0f)
    }

    @Test fun progressBeyondTheGestureIsClamped() {
        assertEquals(playerBackScale(1f, 1080f, 132f), playerBackScale(2f, 1080f, 132f), 0f)
        assertEquals(playerBackScale(0f, 1080f, 132f), playerBackScale(-1f, 1080f, 132f), 0f)
        assertEquals(66f, playerBackSlide(4f, BackEventCompat.EDGE_LEFT, 66f), 0f)
    }

    @Test fun theDriftFollowsTheEdgeTheSwipeStartedFrom() {
        assertEquals(66f, playerBackSlide(1f, BackEventCompat.EDGE_LEFT, 66f), 0f)
        assertEquals(-66f, playerBackSlide(1f, BackEventCompat.EDGE_RIGHT, 66f), 0f)
        assertEquals(33f, playerBackSlide(0.5f, BackEventCompat.EDGE_LEFT, 66f), 0f)
        assertEquals(0f, playerBackSlide(1f, BackEventCompat.EDGE_NONE, 66f), 0f)
    }
}
