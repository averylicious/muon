package dev.avery.muon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingNavTest {
    /** A list consuming a negative delta is moving its content up: the user is scrolling down. */
    @Test fun scrollingDownTucksAndScrollingUpBringsTheBarBack() {
        assertTrue(tuckAfterScroll(tucked = false, consumedY = -12f))
        assertFalse(tuckAfterScroll(tucked = true, consumedY = 12f))
    }

    @Test fun aStepWithinTheSlopLeavesTheBarAsItWas() {
        assertTrue(tuckAfterScroll(tucked = true, consumedY = 0.5f))
        assertFalse(tuckAfterScroll(tucked = false, consumedY = -0.5f))
        assertFalse(tuckAfterScroll(tucked = false, consumedY = 0f))
    }
}
