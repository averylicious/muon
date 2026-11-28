package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostedGlassTest {
    @Test fun onlyFrostedBarsBlurInPortrait() {
        assertTrue(frostedGlassOn(BarSurface.Frosted, sideways = false))
        assertFalse(frostedGlassOn(BarSurface.Tinted, sideways = false))
        assertFalse(frostedGlassOn(BarSurface.Solid, sideways = false))
    }

    @Test fun frostedAndTintedBarsShowTheContentUnderThem() {
        assertTrue(barsSeeThrough(BarSurface.Frosted, sideways = false))
        assertTrue(barsSeeThrough(BarSurface.Tinted, sideways = false))
        assertFalse(barsSeeThrough(BarSurface.Solid, sideways = false))
    }

    /** Sideways the tabs are a rail and the player a side panel: nothing scrolls under a bar. */
    @Test fun nothingIsSeeThroughSideways() {
        for (surface in BarSurface.entries) {
            assertFalse(barsSeeThrough(surface, sideways = true))
            assertFalse(frostedGlassOn(surface, sideways = true))
        }
    }

    @Test fun tintedBarsAreThickerThanFrostedOnesAndSolidOnesOpaque() {
        assertEquals(GLASS_TINT, barTint(BarSurface.Frosted), 0f)
        assertTrue(barTint(BarSurface.Tinted) > barTint(BarSurface.Frosted))
        assertEquals(1f, barTint(BarSurface.Solid), 0f)
    }
}
