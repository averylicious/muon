package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedGroupTest {
    @Test fun aLoneRowIsRoundAllRound() {
        assertEquals(GroupPlace.Only, groupPlace(0, 1))
    }

    @Test fun aGroupHasRoundEndsAndTightJoins() {
        assertEquals(GroupPlace.First, groupPlace(0, 3))
        assertEquals(GroupPlace.Middle, groupPlace(1, 3))
        assertEquals(GroupPlace.Last, groupPlace(2, 3))
    }
}
