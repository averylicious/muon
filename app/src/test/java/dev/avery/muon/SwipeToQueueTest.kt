package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeToQueueTest {
    @Test fun aShortSwipeDoesNothing() {
        assertNull(swipeQueueAction(offset = 100f, width = 1000f))
        assertNull(swipeQueueAction(offset = -279f, width = 1000f))
        assertNull(swipeQueueAction(offset = 500f, width = 0f))
    }

    @Test fun towardsTheEndPlaysNextAndTowardsTheStartAddsToTheQueue() {
        assertEquals(true, swipeQueueAction(offset = 280f, width = 1000f))
        assertEquals(false, swipeQueueAction(offset = -300f, width = 1000f))
    }

    @Test fun theRowFollowsTheFingerThenResistsAndStops() {
        assertEquals(100f, swipeQueueOffset(offset = 0f, delta = 100f, width = 1000f), 0.01f)
        assertEquals(335f, swipeQueueOffset(offset = 300f, delta = 100f, width = 1000f), 0.01f)
        assertEquals(450f, swipeQueueOffset(offset = 440f, delta = 100f, width = 1000f), 0.01f)
        // Coming back is not resisted.
        assertEquals(200f, swipeQueueOffset(offset = 300f, delta = -100f, width = 1000f), 0.01f)
    }
}
