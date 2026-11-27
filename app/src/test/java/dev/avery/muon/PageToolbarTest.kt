package dev.avery.muon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageToolbarTest {
    /** While any part of Play and Shuffle's row is on screen, the header's own buttons serve. */
    @Test fun theToolbarWaitsUntilPlayAndShuffleHaveScrolledAway() {
        assertFalse(pageActionsGone(firstVisibleIndex = 0, actionsIndex = 2))
        assertFalse(pageActionsGone(firstVisibleIndex = 2, actionsIndex = 2))
        assertTrue(pageActionsGone(firstVisibleIndex = 3, actionsIndex = 2))
    }
}
