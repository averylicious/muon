package dev.avery.muon

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModesTest {
    @Test
    fun repeatModeCyclesOffAllOne() {
        val all = nextRepeatMode(Player.REPEAT_MODE_OFF)
        val one = nextRepeatMode(all)
        val off = nextRepeatMode(one)

        assertEquals(Player.REPEAT_MODE_ALL, all)
        assertEquals(Player.REPEAT_MODE_ONE, one)
        assertEquals(Player.REPEAT_MODE_OFF, off)
        assertEquals(listOf("Off", "All", "One"), listOf(
            repeatModeName(off), repeatModeName(all), repeatModeName(one)))
    }
}
