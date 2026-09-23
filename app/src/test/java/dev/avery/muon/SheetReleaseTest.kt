package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Release intent for opening (mini player, upwards: negative travel and velocity) and closing (top
 * bar, downwards: positive). Pixel values stand in for a 2400 px tall sheet with a 600 dp/s, 24 dp
 * flick at 2.625 density; they illustrate the policy, not tuned numbers.
 */
class SheetReleaseTest {
    private val flick = SheetFlick(velocity = 1575f, travel = 63f)
    private val distance = sheetReleaseDistance(height = 2400f, minimum = 126f)

    @Test fun aSlowReleaseMustCoverAShareOfTheSheet() {
        assertEquals(720f, distance, 0f)
        // A short window never asks for less than the gesture's own minimum.
        assertEquals(252f, sheetReleaseDistance(height = 400f, minimum = 252f), 0f)
    }

    @Test fun aSlowShortPullReturnsToWhereItStarted() {
        assertFalse(miniDragOpens(drag = -200f, threshold = distance, velocity = -300f, flick = flick))
        assertFalse(playerDismissCloses(drag = 200f, threshold = distance, velocity = 300f, flick = flick))
    }

    @Test fun aSlowLongPullCommits() {
        assertTrue(miniDragOpens(drag = -800f, threshold = distance, velocity = -300f, flick = flick))
        assertTrue(playerDismissCloses(drag = 800f, threshold = distance, velocity = 300f, flick = flick))
    }

    @Test fun anIntentionalFlickCommitsFromAShortDistance() {
        assertTrue(miniDragOpens(drag = -120f, threshold = distance, velocity = -2500f, flick = flick))
        assertTrue(playerDismissCloses(drag = 120f, threshold = distance, velocity = 2500f, flick = flick))
    }

    @Test fun aStrayJabIsNotAFlick() {
        // Fast, but the finger barely moved: nothing opens or closes by accident.
        assertFalse(miniDragOpens(drag = -20f, threshold = distance, velocity = -4000f, flick = flick))
        assertFalse(playerDismissCloses(drag = 20f, threshold = distance, velocity = 4000f, flick = flick))
    }

    @Test fun aDeliberateReversalReturnsHoweverFarItHadCome() {
        // Pulled well past the distance, then flicked back the other way.
        assertFalse(miniDragOpens(drag = -900f, threshold = distance, velocity = 2500f, flick = flick))
        assertFalse(playerDismissCloses(drag = 900f, threshold = distance, velocity = -2500f, flick = flick))
    }

    @Test fun aSlightDriftBackDoesNotUndoALongPull() {
        // Drifting back slowly is not a flick, so the travel still decides.
        assertTrue(miniDragOpens(drag = -800f, threshold = distance, velocity = 200f, flick = flick))
        assertTrue(playerDismissCloses(drag = 800f, threshold = distance, velocity = -200f, flick = flick))
    }

    @Test fun aFlickCannotRescueAStaleOrIneligibleGesture() {
        assertFalse(miniDragOpens(drag = -900f, threshold = distance, eligible = false,
            velocity = -2500f, flick = flick))
        assertFalse(playerPreviewOpens(previewing = false, startedAt = 2, now = 2, travel = -900f,
            threshold = distance, eligible = true, velocity = -2500f, flick = flick))
        assertFalse(playerDismissCommits(startedAt = 2, now = 3, drag = 900f, threshold = distance,
            velocity = 2500f, flick = flick))
    }

    @Test fun withoutAFlickItIsDistanceAlone() {
        // The earlier distance-only behaviour, which the existing tests still describe.
        assertTrue(sheetReleaseCommits(travel = 720f, velocity = 0f, distance = 720f, flick = SheetFlick.None))
        assertFalse(sheetReleaseCommits(travel = 719f, velocity = 99999f, distance = 720f, flick = SheetFlick.None))
    }
}
