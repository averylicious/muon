package dev.avery.muon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Short gestures on the mini player as the platform actually delivers them: a handful of batched
 * events, pixel positions in the mini player's own coordinates (y grows downwards) and event times
 * in milliseconds. Thresholds are the real ones at 2.625 density on a 2400 px sheet.
 */
class MiniPlayerFlickTest {
    private val A = 7L
    private val B = 8L
    private val flick = SheetFlick(velocity = 1575f, travel = 63f)
    private val distance = sheetReleaseDistance(height = 2400f, minimum = 126f)

    private fun opens(trace: FlickTrace, upMillis: Long, previewing: Boolean = true, startedAt: Int = 1,
        now: Int = 1, eligible: Boolean = true) = playerPreviewOpens(previewing, startedAt, now, trace.travel,
        distance, eligible, trace.releaseVelocity(upMillis), flick)

    /** A brief upward flick: 90 px in 40 ms, as touch-down, a slop-crossing event with one batched point, one move and a moving lift. */
    private fun briefFlick() = FlickTrace().apply {
        down(A, 1_000, 500f)
        sample(A, 1_008, 488f) // batched into the slop-crossing event
        sample(A, 1_016, 470f) // the event that crossed the touch slop
        sample(A, 1_032, 430f)
        sample(A, 1_040, 410f) // the lift, which still moved
    }

    @Test fun twoSamplesAfterTheSlopReadAsNoVelocityAtAll() {
        // How it failed before: only the moves after the slop were recorded, and a brief flick
        // leaves two. The tracker needs three for its fit and returns nothing otherwise.
        val postSlopOnly = VelocityTracker().apply {
            addPosition(1_016, Offset(0f, 0f))
            addPosition(1_032, Offset(0f, -40f))
        }
        assertEquals(0f, postSlopOnly.calculateVelocity().y, 0f)
    }

    @Test fun aBriefUpwardFlickOpensThePlayer() {
        val trace = briefFlick()
        // The whole flick counts, including the part spent inside the slop.
        assertEquals(-90f, trace.travel, 0f)
        assertTrue(trace.releaseVelocity(1_040) < -1575f)
        assertTrue(opens(trace, upMillis = 1_040))
    }

    @Test fun theSameFlickHeldStillBeforeLiftingReturns() {
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_008, 488f)
            sample(A, 1_016, 470f)
            sample(A, 1_032, 430f)
            sample(A, 1_300, 430f) // lifted a quarter of a second later, without moving again
        }
        assertEquals(0f, trace.releaseVelocity(1_300), 0f)
        assertFalse(opens(trace, upMillis = 1_300))
    }

    @Test fun theLiftIsJudgedByItsOwnEventTime() {
        // Judged at the lift's event time the flick is fresh. Had it been judged when the release
        // happened to be processed, 60 ms later, the same flick would have expired.
        val trace = briefFlick()
        assertTrue(trace.releaseVelocity(1_040) < -1575f)
        assertEquals(0f, trace.releaseVelocity(1_100), 0f)
    }

    @Test fun aFastTinyJabIsNotAFlick() {
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_008, 490f)
            sample(A, 1_016, 478f)
            sample(A, 1_020, 470f) // 30 px in 20 ms: fast, but barely moved
        }
        assertFalse(opens(trace, upMillis = 1_020))
    }

    @Test fun aBriefFlickDownwardsOpensNothing() {
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_008, 512f)
            sample(A, 1_016, 530f)
            sample(A, 1_032, 570f)
            sample(A, 1_040, 590f)
        }
        assertEquals(90f, trace.travel, 0f)
        assertFalse(opens(trace, upMillis = 1_040))
    }

    @Test fun aMostlySidewaysGestureHasTooLittleUpwardTravel() {
        // Sideways movement never reaches the vertical slop detector at all; what little vertical
        // travel such a gesture has is far short of a flick.
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_016, 494f)
            sample(A, 1_032, 486f)
            sample(A, 1_040, 480f)
        }
        assertFalse(opens(trace, upMillis = 1_040))
    }

    @Test fun aRealFlickCannotOpenForAStaleOrIneligibleGesture() {
        val trace = briefFlick()
        // Back ended the preview while the finger was still down.
        assertFalse(opens(trace, upMillis = 1_040, previewing = false))
        // A new presentation began under the same finger.
        assertFalse(opens(trace, upMillis = 1_040, startedAt = 1, now = 2))
        // The controller or queue went mid-gesture.
        assertFalse(opens(trace, upMillis = 1_040, eligible = false))
    }

    @Test fun aNewGestureForgetsTheLastOne() {
        val trace = briefFlick()
        trace.down(A, 2_000, 300f)
        trace.sample(A, 2_300, 300f)
        assertEquals(0f, trace.travel, 0f)
        assertEquals(0f, trace.releaseVelocity(2_300), 0f)
    }

    @Test fun aGenuineThreeEventFlickWithAnUnchangedLiftOpens() {
        // Touch-down, one slop-crossing event with no batched points, and a lift reported where the
        // last move was. The unchanged lift adds its time but no fake stop, and two samples suffice.
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_016, 420f)
            sample(A, 1_024, 420f)
        }
        assertEquals(-80f, trace.travel, 0f)
        assertTrue(trace.releaseVelocity(1_024) < -1575f)
        assertTrue(opens(trace, upMillis = 1_024))
    }

    @Test fun aGenuineThreeEventFlickWithAMovingLiftOpens() {
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_016, 440f)
            sample(A, 1_024, 410f)
        }
        assertTrue(opens(trace, upMillis = 1_024))
    }

    @Test fun anotherFingersPositionIsNeverThisGesturesTravel() {
        // The first finger lifts mid-drag and the helpers hand the gesture to a second finger far
        // below it. Read as the same finger, that would be 1000 px of travel in 16 ms.
        val trace = FlickTrace().apply {
            down(A, 1_000, 500f)
            sample(A, 1_008, 488f)
            sample(A, 1_016, 470f)
        }
        assertFalse(trace.sample(B, 1_032, 1_470f))
        assertTrue(trace.abandoned)
        assertEquals(0f, trace.travel, 0f)
        assertEquals(0f, trace.releaseVelocity(1_040), 0f)
        assertFalse(opens(trace, upMillis = 1_040))
        // And the handed-over finger cannot bring it back.
        assertFalse(trace.sample(A, 1_040, 300f))
        assertFalse(opens(trace, upMillis = 1_040))
    }

    @Test fun aSlopCrossedByAnotherFingerIsNotThisGesture() {
        val trace = FlickTrace().apply { down(A, 1_000, 500f) }
        assertFalse(trace.sample(B, 1_016, 200f))
        assertFalse(opens(trace, upMillis = 1_024))
    }
}
