package dev.avery.muon

import androidx.activity.BackEventCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

    /** Shrinking into the mini player, the preview lets go with the sheet, so a closed sheet is exactly the mini player. */
    @Test fun thePreviewLetsGoAsThePlayerShrinksIntoTheMiniPlayer() {
        assertEquals(1f, playerBackHeld(0f, morphing = true), 0f)
        assertEquals(0.75f, playerBackHeld(0.25f, morphing = true), 1e-6f)
        assertEquals(0f, playerBackHeld(1f, morphing = true), 0f)
        assertEquals(0f, playerBackHeld(1.5f, morphing = true), 0f)
        assertEquals(1f, playerBackHeld(-0.5f, morphing = true), 0f)
    }

    /** Sliding away below the screen, it leaves at the size the gesture left it. */
    @Test fun aPlayerThatSlidesAwayKeepsItsPreview() {
        assertEquals(1f, playerBackHeld(0f, morphing = false), 0f)
        assertEquals(1f, playerBackHeld(0.6f, morphing = false), 0f)
        assertEquals(1f, playerBackHeld(1f, morphing = false), 0f)
    }

    /** The flying cover is placed as the surface's layer places everything in it: scaled about the middle, then moved. */
    @Test fun aRectangleIsPlacedAsTheSurfaceIsDrawn() {
        val surface = Size(1000f, 2000f)
        val cover = Rect(100f, 200f, 900f, 1000f)
        assertEquals(cover, playerBackPlaced(cover, surface, scale = 1f, slideX = 0f, slideY = 0f))
        assertEquals(Rect(140f, 280f, 860f, 1000f), playerBackPlaced(cover, surface, scale = 0.9f, slideX = 0f, slideY = 0f))
        assertEquals(Rect(164f, 260f, 884f, 980f), playerBackPlaced(cover, surface, scale = 0.9f, slideX = 24f, slideY = -20f))
        // The middle of the surface stays where it is, whatever the scale.
        assertEquals(Rect(500f, 1000f, 500f, 1000f), playerBackPlaced(Rect(500f, 1000f, 500f, 1000f), surface, 0.5f, 0f, 0f))
    }
}
