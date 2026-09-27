package dev.avery.muon

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverBackdropTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = p shr 16 and 0xFF

    @Test fun aPlainCoverStaysThatColour() {
        val plain = IntArray(8 * 8) { rgb(40, 120, 200) }
        assertArrayEquals(plain, boxBlur(plain, 8, 8, radius = 2, passes = 3))
    }

    @Test fun aBrightSpotSpreadsIntoItsNeighbours() {
        val pixels = IntArray(9 * 9) { rgb(0, 0, 0) }
        pixels[4 * 9 + 4] = rgb(255, 255, 255)
        val blurred = boxBlur(pixels, 9, 9, radius = 1, passes = 1)
        assertTrue(red(blurred[4 * 9 + 4]) in 1 until 255)
        assertTrue(red(blurred[4 * 9 + 5]) > 0)
        assertTrue(red(blurred[3 * 9 + 3]) > 0)
        assertEquals(0, red(blurred[0]))
    }

    @Test fun edgesRepeatRatherThanDarken() {
        // A white cover must not grow a dark rim from blurring past its border.
        val white = IntArray(6 * 4) { rgb(255, 255, 255) }
        val blurred = boxBlur(white, 6, 4, radius = 3, passes = 2)
        assertTrue(blurred.all { red(it) == 255 })
    }

    @Test fun theResultIsOpaqueAndTheInputUntouched() {
        val pixels = IntArray(4 * 4) { rgb(it * 10, 0, 0) }
        val before = pixels.copyOf()
        val blurred = boxBlur(pixels, 4, 4, radius = 1, passes = 2)
        assertArrayEquals(before, pixels)
        assertTrue(blurred.all { it ushr 24 == 0xFF })
    }

    @Test fun pureBlackKeepsItsBlackPlayer() {
        assertTrue(showCoverBackdrop(blur = true, background = Color(0xFF101411)))
        assertFalse(showCoverBackdrop(blur = true, background = Color.Black))
        assertFalse(showCoverBackdrop(blur = false, background = Color(0xFF101411)))
    }
}
