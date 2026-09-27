package dev.avery.muon

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkThemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun textKeepsItsContrastWhateverTheCover() {
        for (dark in listOf(false, true)) for (hue in 0 until 360 step 15) {
            val base = if (dark) darkColorScheme() else lightColorScheme()
            val s = artworkScheme(base, hsl(hue.toFloat(), 1f, 0.5f), dark, pureBlack = false)
            val where = "hue $hue, dark $dark"
            assertTrue(where, contrast(s.onSurface, s.background) >= 7f)
            assertTrue(where, contrast(s.onSurfaceVariant, s.background) >= 4.5f)
            assertTrue(where, contrast(s.onPrimaryContainer, s.primaryContainer) >= 4.5f)
            assertTrue(where, contrast(s.onSecondaryContainer, s.secondaryContainer) >= 4.5f)
            assertTrue(where, contrast(s.onPrimary, s.primary) >= 3f)
            assertTrue(where, contrast(s.primary, s.background) >= 3f)
        }
    }

    @Test fun pureBlackKeepsItsSurfaces() {
        val base = darkColorScheme(background = Color.Black, surface = Color.Black)
        val s = artworkScheme(base, hsl(200f, 1f, 0.5f), dark = true, pureBlack = true)
        assertEquals(Color.Black, s.background)
        assertTrue(s.primary != base.primary)
    }

    @Test fun hslRoundTrips() {
        val (hue, saturation) = hueAndSaturation(hsl(210f, 0.6f, 0.5f))
        assertEquals(210f, hue, 1.5f)
        assertEquals(0.6f, saturation, 0.02f)
    }

    @Test fun theVividHueLeads() {
        val grey = 0xFF808080.toInt()
        val red = hsl(0f, 0.9f, 0.5f)
        // Mostly grey, with a red accent: red is the seed.
        val (hue, saturation) = hueAndSaturation(coverSeed(List(80) { grey } + List(20) { red })!!)
        assertEquals(0f, minOf(hue, 360f - hue), 2f)
        assertTrue(saturation > 0.8f)
    }

    @Test fun aGreyCoverGivesAGreySeed() {
        val (_, saturation) = hueAndSaturation(coverSeed(List(50) { 0xFF404040.toInt() } + List(50) { 0xFFB0B0B0.toInt() })!!)
        assertEquals(0f, saturation, 0.01f)
        assertNull(coverSeed(emptyList()))
    }
}
