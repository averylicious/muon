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

    /** The 2.0 screens are not built yet, so nothing else would notice a missing mapping. */
    @Test fun kindsAddedForTwoPointZeroAreMappedRatherThanFallingBack() {
        val fallback = iconRes("nonexistent")
        listOf("back", "collapse", "close", "queue", "lyrics", "play-next", "add-queue",
            "album", "artist", "delete", "drag-handle", "volume-low").forEach {
            assertTrue("$it must be listed in ICON_KINDS", it in ICON_KINDS)
            assertNotEquals("$it must have its own drawable, not the fallback", fallback, iconRes(it))
        }
    }
}
