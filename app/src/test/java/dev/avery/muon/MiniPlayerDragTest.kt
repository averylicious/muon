package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerDragTest {
    @Test fun aLongDragCannotOpenAfterEligibilityIsLost() {
        assertTrue(miniDragOpens(-400f, 132f, eligible = true))
        // The same completed distance must not open outgoing/empty/disconnected content.
        assertFalse(miniDragOpens(-400f, 132f, eligible = false))
    }

    @Test fun pullingDownDoesNothingAtAll() {
        // A closed sheet pulled down stays closed, and it is not a way to open the player.
        assertEquals(1f, playerSheetDragged(baseline = 1f, travel = 200f, height = 2400f), 0f)
        assertFalse(miniDragOpens(400f, 132f))
    }

    @Test fun theWholePlayerFollowsTheFingerUp() {
        // No resistance any more: a quarter of the screen of travel is a quarter of the way open.
        assertEquals(0.75f, playerSheetDragged(baseline = 1f, travel = -600f, height = 2400f), 1e-6f)
        assertEquals(0f, playerSheetDragged(baseline = 1f, travel = -9000f, height = 2400f), 0f)
    }

    @Test fun openingTakesADeliberatePullPastTheThreshold() {
        assertTrue(miniDragOpens(-132f, 132f))
        assertTrue(miniDragOpens(-400f, 132f))
        // A short pull puts the player away again, even though it rose for it.
        assertFalse(miniDragOpens(-131f, 132f))
        assertTrue(playerSheetDragged(baseline = 1f, travel = -131f, height = 2400f) < 1f)
    }

    @Test fun aPreviewOnlyOpensWhileItIsStillThisGesturesOwn() {
        assertTrue(playerPreviewOpens(previewing = true, startedAt = 4, now = 4, travel = -400f,
            threshold = 132f, eligible = true))
        // Back ended the preview while the finger stayed down: letting go afterwards opens nothing.
        assertFalse(playerPreviewOpens(previewing = false, startedAt = 4, now = 4, travel = -400f,
            threshold = 132f, eligible = true))
        // A new presentation began under the same finger: its travel belongs to the old one.
        assertFalse(playerPreviewOpens(previewing = true, startedAt = 4, now = 5, travel = -400f,
            threshold = 132f, eligible = true))
        // The controller or queue went mid-preview.
        assertFalse(playerPreviewOpens(previewing = true, startedAt = 4, now = 4, travel = -400f,
            threshold = 132f, eligible = false))
    }

    @Test fun lateMovesAfterBackAreRefused() {
        assertTrue(playerPreviewOwned(previewing = true, startedAt = 2, now = 2))
        assertFalse(playerPreviewOwned(previewing = false, startedAt = 2, now = 2))
        assertFalse(playerPreviewOwned(previewing = true, startedAt = 2, now = 3))
    }

    @Test fun whereTheSheetAlreadyWasDoesNotCountTowardsOpening() {
        // Taken over while still settling away, 20% from open: it is nearly open already, but the
        // finger has only moved a little, so letting go puts it away rather than opening it.
        assertEquals(0.18f, playerSheetDragged(baseline = 0.2f, travel = -48f, height = 2400f), 1e-6f)
        assertFalse(playerPreviewOpens(previewing = true, startedAt = 1, now = 1, travel = -48f,
            threshold = 132f, eligible = true))
    }

    @Test fun aDragDownFromRestBeginsNothing() {
        // Slop crossed downwards: no preview, so no scrim and the library stays accessible.
        assertFalse(playerPreviewMayBegin(travel = 30f))
        assertFalse(playerPreviewMayBegin(travel = 0f))
        assertTrue(playerPreviewMayBegin(travel = -1f))
    }

    @Test fun aDragThatDipsBeforeRisingBeginsOnlyOnceItGoesUp() {
        // Down 30, then up 32: it begins at the moment the finger is above where it started.
        var travel = 30f
        assertFalse(playerPreviewMayBegin(travel))
        travel -= 32f
        assertTrue(playerPreviewMayBegin(travel))
        // And the sheet then sits just that far up, not where the dip left off.
        assertEquals(1f - 2f / 2400f, playerSheetDragged(baseline = 1f, travel = travel, height = 2400f), 1e-6f)
    }
}
