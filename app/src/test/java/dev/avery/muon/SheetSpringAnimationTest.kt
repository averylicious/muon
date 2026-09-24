package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs the sheet's real spring on a real, bounded `Animatable`, driven by a frame clock that advances
 * exactly one millisecond per frame, so the outcome does not depend on a device or on timing.
 */
class SheetSpringAnimationTest {
    private class StepClock : MonotonicFrameClock {
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            now += 1_000_000L
            return onFrame(now)
        }
    }

    private fun sheetAt(position: Float) = Animatable(position).apply { updateBounds(0f, 1f) }

    @Test fun speedAwayFromTheTargetStopsARawBoundedSpringAtTheWrongEdge() = runBlocking(StepClock()) {
        // The review finding on the raw API: nearly closed and still moving towards closed, then
        // asked to open. The spring first carries on, reaches the closed bound, and stops there.
        val sheet = sheetAt(0.99f)
        val result = sheet.animateTo(0f, SheetSpring, initialVelocity = 6f)
        assertEquals(AnimationEndReason.BoundReached, result.endReason)
        assertEquals(1f, sheet.value, 0f)
    }

    @Test fun aReopenNearTheEndOfACloseStillArrivesOpen() = runBlocking(StepClock()) {
        val sheet = sheetAt(0.99f)
        sheet.settleSheet(target = 0f, velocity = 6f)
        assertEquals(0f, sheet.value, 0f)
    }

    @Test fun aReturnCarryingSpeedTheOtherWayStillArrivesClosed() = runBlocking(StepClock()) {
        // The mirror case: nearly open, moving up, but the release decided to put it away.
        val sheet = sheetAt(0.01f)
        sheet.settleSheet(target = 1f, velocity = -6f)
        assertEquals(1f, sheet.value, 0f)
    }

    @Test fun speedTowardsTheTargetIsKeptAndEndsExactlyThere() = runBlocking(StepClock()) {
        // A flick's speed carries the sheet on; if it would overshoot, the bound at the target stops
        // it exactly on arrival.
        val closing = sheetAt(0.5f)
        closing.settleSheet(target = 1f, velocity = 6f)
        assertEquals(1f, closing.value, 0f)
        val opening = sheetAt(0.5f)
        opening.settleSheet(target = 0f, velocity = -6f)
        assertEquals(0f, opening.value, 0f)
    }

    @Test fun onlySpeedTowardsTheTargetIsHandedOn() {
        assertEquals(6f, sheetSettleVelocity(target = 1f, from = 0.5f, velocity = 6f), 0f)
        assertEquals(-6f, sheetSettleVelocity(target = 0f, from = 0.5f, velocity = -6f), 0f)
        assertEquals(0f, sheetSettleVelocity(target = 0f, from = 0.99f, velocity = 6f), 0f)
        assertEquals(0f, sheetSettleVelocity(target = 1f, from = 0.01f, velocity = -6f), 0f)
        // Already there: nothing to hand on.
        assertEquals(0f, sheetSettleVelocity(target = 1f, from = 1f, velocity = 6f), 0f)
    }
}
