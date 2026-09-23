package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetTurnTest {
    @Test fun aPreviewThatBeginsAndEndsUnseenStillCompletes() {
        val closed = SheetTurn()
        // Begun and ended between two frames: the flag is back where it started...
        val after = closed.begin().end()
        assertEquals(closed.previewing, after.previewing)
        // ...but the completion is new, so whatever settles the sheet still sees that it ended.
        assertEquals(closed.completions + 1, after.completions)
        assertNotEquals(closed, after)
    }

    @Test fun aRestartedPreviewNeverSharesItsToken() {
        val first = SheetTurn().begin()
        // Cancelled and begun again before anything was presented.
        val second = first.end().begin()
        assertNotEquals(first.generation, second.generation)
        // The old detector's token no longer owns the new preview; the new one does.
        assertFalse(playerPreviewOwned(second.previewing, first.generation, second.generation))
        assertTrue(playerPreviewOwned(second.previewing, second.generation, second.generation))
    }

    @Test fun backEndsThePreviewForAFingerStillDown() {
        val previewing = SheetTurn().begin()
        val token = previewing.generation
        val afterBack = previewing.end()
        assertFalse(playerPreviewOwned(afterBack.previewing, token, afterBack.generation))
        assertFalse(playerPreviewOpens(afterBack.previewing, token, afterBack.generation,
            travel = -400f, threshold = 132f, eligible = true))
    }

    @Test fun aPresentationRetiresEveryEarlierToken() {
        val opened = SheetTurn().present()
        val dismissToken = opened.generation
        val closedAgain = opened.present()
        // A dismiss drag begun on the earlier presentation cannot close the next one.
        assertFalse(playerDismissCommits(dismissToken, closedAgain.generation, drag = 900f, threshold = 264f))
    }

    @Test fun endingTwiceCountsOnce() {
        // A release followed by the detector's own teardown both end the same preview.
        val ended = SheetTurn().begin().end()
        assertEquals(ended, ended.end())
    }

    @Test fun presentingDoesNotLookLikeACompletedPreview() {
        // Presentation changes the generation only, so settling it cannot trigger another settle.
        val turn = SheetTurn(generation = 3, completions = 2)
        assertEquals(turn.completions, turn.present().completions)
        assertEquals(turn.previewing, turn.present().previewing)
    }
}
