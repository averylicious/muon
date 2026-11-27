package dev.avery.muon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WholeTileTest {
    @Test fun onlyATileStartingAboveTheGridIsBroughtIntoView() {
        assertTrue(tileHiddenAbove(-40))
        assertFalse(tileHiddenAbove(0))
        assertFalse(tileHiddenAbove(300))
    }
}
