package dev.avery.muon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLifecycleTest {
    @Test fun aLiveControllerWithNothingLoadedClosesTheOverlay() {
        assertTrue(overlayShouldClose(controllerAttached = true, hasCurrentItem = false))
    }

    @Test fun playbackKeepsTheOverlayOpen() {
        assertFalse(overlayShouldClose(controllerAttached = true, hasCurrentItem = true))
    }

    /** onStop releases the controller, and rotation restores the flag before one reconnects. */
    @Test fun anAbsentControllerNeverClosesTheOverlay() {
        assertFalse(overlayShouldClose(controllerAttached = false, hasCurrentItem = false))
        assertFalse(overlayShouldClose(controllerAttached = false, hasCurrentItem = true))
    }
}
