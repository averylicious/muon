package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class BlurBehindTest {
    @Test fun aClosedPlayerLeavesTheLibrarySharp() {
        assertEquals(0f, blurBehindAmount(sheetPosition = 1f, menu = 0f), 0f)
    }

    @Test fun theBlurFollowsHowFarThePlayerHasRisenEasedIn() {
        assertEquals(1f, blurBehindAmount(sheetPosition = 0f, menu = 0f), 0f)
        assertEquals(0.0625f, blurBehindAmount(sheetPosition = 0.75f, menu = 0f), 1e-6f)
        // A short pull on the mini player barely blurs at all.
        assertEquals(0.01f, blurBehindAmount(sheetPosition = 0.9f, menu = 0f), 1e-6f)
    }

    @Test fun theSongMenuBlursOnItsOwnAndTheStrongerOfTheTwoWins() {
        assertEquals(1f, blurBehindAmount(sheetPosition = 1f, menu = 1f), 0f)
        assertEquals(0.36f, blurBehindAmount(sheetPosition = 0.4f, menu = 0.3f), 1e-6f)
        assertEquals(0.5f, blurBehindAmount(sheetPosition = 0.8f, menu = 0.5f), 1e-6f)
    }

    @Test fun valuesOutsideTheRangeAreClamped() {
        assertEquals(0f, blurBehindAmount(sheetPosition = 1.2f, menu = -0.5f), 0f)
        assertEquals(1f, blurBehindAmount(sheetPosition = -0.1f, menu = 0f), 0f)
    }
}
