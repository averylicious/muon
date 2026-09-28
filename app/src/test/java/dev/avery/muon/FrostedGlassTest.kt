package dev.avery.muon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedGlassTest {
    @Test fun glassWithBlurOnAndroid12InPortrait() {
        assertTrue(frostedGlassOn(blur = true, sdk = 31, sideways = false))
        assertTrue(frostedGlassOn(blur = true, sdk = 37, sideways = false))
    }

    /** The experiment's Blur switch off keeps the bars solid, as before. */
    @Test fun solidWithBlurOff() {
        assertFalse(frostedGlassOn(blur = false, sdk = 37, sideways = false))
    }

    /** `RenderEffect` arrived in Android 12 (API 31); older phones keep solid bars. */
    @Test fun solidBeforeAndroid12() {
        assertFalse(frostedGlassOn(blur = true, sdk = 30, sideways = false))
    }

    /** Sideways the tabs are a rail and the player a side panel: nothing scrolls under a bar. */
    @Test fun solidSideways() {
        assertFalse(frostedGlassOn(blur = true, sdk = 37, sideways = true))
    }
}
