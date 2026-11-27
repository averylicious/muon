package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSummaryTest {
    @Test fun summaryCountsSizeAndPlace() {
        assertEquals("190 songs · 1.4 GB · phone and SD card", savedSummary(190, 38_000_000, 1_400_000_000 - 38_000_000, onCard = true))
        assertEquals("1 song · 9 MB · this phone", savedSummary(1, 9_000_000, 0, onCard = false))
    }

    @Test fun summaryLeavesOutAnUnknownSize() {
        assertEquals("3 songs · this phone", savedSummary(3, null, 5_000_000, onCard = false))
    }

    @Test fun songSizesKeepOneDecimal() {
        assertEquals("9.4 MB", songSize(9_400_000))
        assertEquals("1.0 MB", songSize(1_000_000))
        assertEquals("120 MB", songSize(120_000_000))
        assertEquals("800 kB", songSize(800_000))
    }
}
