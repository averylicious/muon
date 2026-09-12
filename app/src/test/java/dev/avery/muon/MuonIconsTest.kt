package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MuonIconsTest {
    @Test fun everyKindResolvesToItsOwnDrawable() {
        val resolved = ICON_KINDS.associateWith { iconRes(it) }
        assertTrue(resolved.values.all { it != 0 })
        assertEquals("icon kinds must not share a drawable", ICON_KINDS.size, resolved.values.toSet().size)
    }

    @Test fun repeatOneIsDistinctFromRepeat() {
        assertNotEquals(iconRes("repeat"), iconRes("repeat-one"))
    }

    @Test fun unknownKindFallsBackToSettings() {
        assertEquals(iconRes("settings"), iconRes("nonexistent"))
    }
}
