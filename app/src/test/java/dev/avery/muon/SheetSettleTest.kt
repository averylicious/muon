package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetSettleTest {
    @Test fun aReleaseAlreadyHeadingTheRightWayKeepsItsSpeed() {
        // Dismissed by a flick: the drag set the sheet off towards closed, and the presentation that
        // follows must let it carry on rather than restart it from rest.
        assertTrue(playerSheetKeepsSettle(heading = 1f, target = 1f, running = true))
        assertTrue(playerSheetKeepsSettle(heading = 0f, target = 0f, running = true))
    }

    @Test fun aSettleGoingTheOtherWayIsReplaced() {
        // The drag sent it towards open but the open was refused: the presentation puts it away.
        assertFalse(playerSheetKeepsSettle(heading = 0f, target = 1f, running = true))
        assertFalse(playerSheetKeepsSettle(heading = 1f, target = 0f, running = true))
    }

    @Test fun nothingRunningMeansThePresentationStartsItsOwn() {
        assertFalse(playerSheetKeepsSettle(heading = 1f, target = 1f, running = false))
        // Stopped by a drag or a new owner: no heading is left to keep.
        assertFalse(playerSheetKeepsSettle(heading = null, target = 1f, running = true))
    }

    @Test fun theFingersSpeedBecomesTheSheetsInItsOwnUnits() {
        // 1200 px/s on a 2400 px sheet is half the sheet per second, with the direction kept.
        assertEquals(0.5f, sheetFractionVelocity(pixelsPerSecond = 1200f, height = 2400f), 1e-6f)
        assertEquals(-0.5f, sheetFractionVelocity(pixelsPerSecond = -1200f, height = 2400f), 1e-6f)
        // An expired flick hands on nothing, and neither does an unmeasured sheet.
        assertEquals(0f, sheetFractionVelocity(pixelsPerSecond = 0f, height = 2400f), 0f)
        assertEquals(0f, sheetFractionVelocity(pixelsPerSecond = 1200f, height = 0f), 0f)
    }
}
