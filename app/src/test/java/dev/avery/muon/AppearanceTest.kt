package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceTest {
    @Test fun materialYouIsTheDefaultForMissingOrUnreadableValues() {
        assertEquals(PaletteChoice.MaterialYou, paletteChoiceFrom(null))
        assertEquals(PaletteChoice.MaterialYou, paletteChoiceFrom(""))
        assertEquals(PaletteChoice.MaterialYou, paletteChoiceFrom("Amoled"))
        assertEquals(PaletteChoice.MaterialYou, paletteChoiceFrom("muon"))
    }

    @Test fun storedChoiceRoundTrips() {
        for (choice in PaletteChoice.entries) {
            assertEquals(choice, paletteChoiceFrom(choice.name))
        }
    }

    @Test fun dynamicColourNeedsAndroid12() {
        assertEquals(false, dynamicColorAvailable(28))
        assertEquals(false, dynamicColorAvailable(30))
        assertEquals(true, dynamicColorAvailable(31))
        assertEquals(true, dynamicColorAvailable(36))
    }

    @Test fun materialYouFallsBackToTheMuonPaletteWithoutDynamicColour() {
        assertEquals(PaletteChoice.Muon, effectivePalette(PaletteChoice.MaterialYou, false))
        assertEquals(PaletteChoice.MaterialYou, effectivePalette(PaletteChoice.MaterialYou, true))
        assertEquals(PaletteChoice.Muon, effectivePalette(PaletteChoice.Muon, true))
        assertEquals(PaletteChoice.Muon, effectivePalette(PaletteChoice.Muon, false))
    }

    @Test fun pureBlackOnlyAppliesInDarkMode() {
        assertEquals(true, useBlackSurfaces(amoled = true, darkTheme = true))
        assertEquals(false, useBlackSurfaces(amoled = true, darkTheme = false))
        assertEquals(false, useBlackSurfaces(amoled = false, darkTheme = true))
        assertEquals(false, useBlackSurfaces(amoled = false, darkTheme = false))
    }

    @Test fun everyChoiceHasLabelAndDescription() {
        for (choice in PaletteChoice.entries) {
            assertEquals(true, paletteLabel(choice).isNotBlank())
            assertEquals(true, paletteDescription(choice).isNotBlank())
        }
    }
}
