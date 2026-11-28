package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropTest {
    @Test fun frostedIsGlassOnlyWithTheBackdrop() {
        assertEquals(GlassLook.Glass, glassLook(BarSurface.Frosted, backdrop = true))
        assertEquals(GlassLook.Solid, glassLook(BarSurface.Frosted, backdrop = false))
    }

    @Test fun tintedAndSolidIgnoreTheBackdrop() {
        for (backdrop in listOf(true, false)) {
            assertEquals(GlassLook.Tinted, glassLook(BarSurface.Tinted, backdrop))
            assertEquals(GlassLook.Solid, glassLook(BarSurface.Solid, backdrop))
        }
    }
}
