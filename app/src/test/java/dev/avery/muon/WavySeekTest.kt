package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class WavySeekTest {
    @Test fun flatWhenStill() {
        assertEquals(0f, seekWaveY(13f, 36f, 0.4f, 0f), 0f)
    }

    @Test fun peaksAQuarterWaveAlong() {
        assertEquals(3f, seekWaveY(9f, 36f, 0f, 3f), 1e-4f)
        assertEquals(-3f, seekWaveY(27f, 36f, 0f, 3f), 1e-4f)
    }

    @Test fun rollingAWholeWaveComesBackToTheSameShape() {
        assertEquals(seekWaveY(5f, 36f, 0f, 3f), seekWaveY(5f, 36f, 1f, 3f), 1e-4f)
    }
}
