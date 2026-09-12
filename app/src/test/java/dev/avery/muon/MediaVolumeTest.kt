package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaVolumeTest {
    @Test
    fun volumeRangeUsesDeviceMinimumAndClampsChanges() {
        assertEquals(MediaVolumeState(2, 12, 7, false), normalizedVolumeState(2, 12, 7, false))
        assertEquals(50, normalizedVolumeState(2, 12, 7, false).percent)
        assertEquals(2, normalizedVolumeState(2, 12, -1, false).current)
        assertEquals(12, normalizedVolumeState(2, 12, 20, false).current)
    }
}
