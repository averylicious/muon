package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {
    @Test fun fractionIsClampedAndUnknownDurationsReadAsZero() {
        assertEquals(0.5f, progressFraction(30_000, 60_000), 0.0001f)
        assertEquals(0f, progressFraction(30_000, 0), 0.0001f)
        assertEquals(0f, progressFraction(30_000, -1), 0.0001f)
        assertEquals(0f, progressFraction(-5_000, 60_000), 0.0001f)
        assertEquals(1f, progressFraction(90_000, 60_000), 0.0001f)
    }
}
