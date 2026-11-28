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

    @Test fun barSurfaceIsFrostedUntilChosen() {
        assertEquals(BarSurface.Frosted, barSurfaceFrom(null))
        assertEquals(BarSurface.Frosted, barSurfaceFrom("tinted"))
        for (choice in BarSurface.entries) assertEquals(choice, barSurfaceFrom(choice.name))
    }

    @Test fun blurOffTurnsFrostedBarsSolid() {
        assertEquals(BarSurface.Solid, effectiveBarSurface(BarSurface.Frosted, blur = false, sdkInt = 37))
        assertEquals(BarSurface.Frosted, effectiveBarSurface(BarSurface.Frosted, blur = true, sdkInt = 37))
    }

    @Test fun frostedBarsAreSolidBeforeAndroid12() {
        assertEquals(BarSurface.Solid, effectiveBarSurface(BarSurface.Frosted, blur = true, sdkInt = 30))
        assertEquals(BarSurface.Frosted, effectiveBarSurface(BarSurface.Frosted, blur = true, sdkInt = 31))
    }

    @Test fun tintedAndSolidIgnoreBlurAndAndroidVersion() {
        for (blur in listOf(true, false)) for (sdk in listOf(28, 37)) {
            assertEquals(BarSurface.Tinted, effectiveBarSurface(BarSurface.Tinted, blur, sdk))
            assertEquals(BarSurface.Solid, effectiveBarSurface(BarSurface.Solid, blur, sdk))
        }
    }

    @Test fun everyBarSurfaceHasALabel() {
        for (choice in BarSurface.entries) assertEquals(true, barSurfaceLabel(choice).isNotBlank())
    }

    @Test fun navigationBarIsEdgeToEdgeUntilFloatingIsChosen() {
        assertEquals(NavBarStyle.EdgeToEdge, navBarStyleFrom(null))
        assertEquals(NavBarStyle.EdgeToEdge, navBarStyleFrom("floating"))
        for (style in NavBarStyle.entries) assertEquals(style, navBarStyleFrom(style.name))
    }

    @Test fun everyNavigationBarStyleHasALabel() {
        for (style in NavBarStyle.entries) assertEquals(true, navBarStyleLabel(style).isNotBlank())
    }

    @Test fun floatingSizesOutsideTheirRangeFallBackIntoIt() {
        assertEquals(1f, floatWidthFrom(Float.NaN), 0f)
        assertEquals(FLOAT_WIDTH_MIN, floatWidthFrom(0.1f), 0f)
        assertEquals(1f, floatWidthFrom(1.4f), 0f)
        assertEquals(0.8f, floatWidthFrom(0.8f), 0f)
        assertEquals(FLOAT_TAB_HEIGHT_MIN, floatTabHeightFrom(10))
        assertEquals(FLOAT_TAB_HEIGHT_MAX, floatTabHeightFrom(200))
        assertEquals(48, floatTabHeightFrom(48))
    }

    @Test fun theDefaultTabHeightIsAChoiceOnTheSlider() {
        assertEquals(0, (FLOAT_TAB_HEIGHT_DEFAULT - FLOAT_TAB_HEIGHT_MIN) % FLOAT_TAB_HEIGHT_STEP)
        assertEquals(0, (FLOAT_TAB_HEIGHT_MAX - FLOAT_TAB_HEIGHT_MIN) % FLOAT_TAB_HEIGHT_STEP)
    }
}
