package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackIdentityTest {
    private fun track(id: Long) = TauonTrack(id, "T$id", "A", "B", 1000, true, false)

    @Test fun uniqueTracksKeyByIdAlone() {
        assertEquals(listOf("7", "3", "11"), trackKeys(listOf(track(7), track(3), track(11))))
    }

    @Test fun repeatedTracksStayDistinct() {
        val keys = trackKeys(listOf(track(4), track(9), track(4), track(4)))
        assertEquals(listOf("4", "9", "4#2", "4#3"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun keysDoNotDependOnPosition() {
        val moved = trackKeys(listOf(track(9), track(4)))
        assertEquals(listOf("9", "4"), moved)
        // The same track keeps its key after the list is reordered, which index-based keys did not.
        assertEquals(trackKeys(listOf(track(4), track(9)))[0], moved[1])
    }

    @Test fun emptyListHasNoKeys() {
        assertEquals(emptyList<String>(), trackKeys(emptyList()))
    }
}
