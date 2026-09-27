package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayGainTest {
    @Test fun readsRsgainValues() {
        assertEquals(-12.62f, parseGainDb("-12.62 dB")!!, 0.001f)
        assertEquals(1.5f, parseGainDb("+1.5 dB")!!, 0.001f)
        assertEquals(-3f, parseGainDb(" -3 ")!!, 0.001f)
    }

    @Test fun rejectsDamagedGains() {
        assertNull(parseGainDb(null))
        assertNull(parseGainDb("loud"))
        assertNull(parseGainDb("-90 dB"))
        assertNull(parseGainDb("NaN dB"))
    }

    @Test fun trackGainWinsOverR128() {
        val tags = mapOf("REPLAYGAIN_TRACK_GAIN" to "-11.29 dB", "REPLAYGAIN_TRACK_PEAK" to "0.999908",
            "R128_TRACK_GAIN" to "-1280")
        assertEquals(TrackLoudness(-11.29f, 0.999908f), trackLoudness(tags))
    }

    @Test fun r128IsMovedToTheReplayGainReference() {
        // -1280 / 256 = -5 dB against -23 LUFS, which is 0 dB against ReplayGain's -18 LUFS.
        assertEquals(0f, trackLoudness(mapOf("R128_TRACK_GAIN" to "-1280"))!!.gainDb, 0.001f)
    }

    @Test fun untaggedSongsHaveNoLoudness() {
        assertNull(trackLoudness(mapOf("TITLE" to "Pray")))
    }

    @Test fun gainIsNeverAboveZero() {
        assertEquals(0f, appliedGainDb(TrackLoudness(4f)), 0.001f)
        assertEquals(-12.62f, appliedGainDb(TrackLoudness(-12.62f, 0.999969f)), 0.001f)
    }

    @Test fun peakLimitsTheGain() {
        // A peak of 2, over full scale, needs 6 dB off even when the track gain asks for less.
        assertEquals(-6.02f, appliedGainDb(TrackLoudness(-2f, 2f)), 0.01f)
    }

    @Test fun volumeFollowsDecibels() {
        assertEquals(1f, volumeForGain(0f), 0.0001f)
        assertEquals(0.5012f, volumeForGain(-6f), 0.001f)
        assertEquals(0.1f, volumeForGain(-20f), 0.0001f)
    }
}
