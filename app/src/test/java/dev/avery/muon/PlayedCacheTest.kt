package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class PlayedCacheTest {
    @Test fun playedCopiesHaveTheirOwnKeys() {
        assertEquals("played:http://192.168.1.10:7814/42", playedKey("http://192.168.1.10:7814/42"))
        assertTrue(playedKey("x").startsWith(PLAYED_PREFIX))
    }

    @Test fun theDefaultIsTwoGigabytesAndIsOffered() {
        assertEquals(2_000_000_000L, DEFAULT_CACHE_LIMIT)
        assertTrue(DEFAULT_CACHE_LIMIT in CACHE_LIMITS)
        assertEquals("2 GB", formatBytes(DEFAULT_CACHE_LIMIT))
        assertEquals("10 GB", formatBytes(10 * GIGABYTE))
    }

    @Test fun fullMeansWithinFivePercentOfTheLimit() {
        assertFalse(cacheFull(1_800_000_000, DEFAULT_CACHE_LIMIT))
        assertTrue(cacheFull(1_900_000_000, DEFAULT_CACHE_LIMIT))
        assertTrue(cacheFull(2_000_000_000, DEFAULT_CACHE_LIMIT))
        assertFalse(cacheFull(0, 0))
    }
}
