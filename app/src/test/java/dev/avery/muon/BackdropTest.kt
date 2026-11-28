package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropTest {
    @Test fun frostedIsGlassOnlyWithTheBackdrop() {
        assertEquals(ToolbarLook.Glass, toolbarLook(BarSurface.Frosted, backdrop = true))
        assertEquals(ToolbarLook.Solid, toolbarLook(BarSurface.Frosted, backdrop = false))
    }

    @Test fun tintedAndSolidIgnoreTheBackdrop() {
        for (backdrop in listOf(true, false)) {
            assertEquals(ToolbarLook.Tinted, toolbarLook(BarSurface.Tinted, backdrop))
            assertEquals(ToolbarLook.Solid, toolbarLook(BarSurface.Solid, backdrop))
        }
    }
}
