package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class QueueModelTest {
    private val unset = -1

    @Test fun nextUpFollowsThePlayersOrderNotTheList() {
        // A shuffled order over five songs: 2 → 4 → 0 → 3 → 1.
        val shuffled = mapOf(2 to 4, 4 to 0, 0 to 3, 3 to 1, 1 to unset)
        assertEquals(listOf(4, 0, 3, 1), upNextOrder(2, 5) { shuffled.getValue(it) })
        // In list order from the third song.
        assertEquals(listOf(3, 4), upNextOrder(2, 5) { if (it + 1 < 5) it + 1 else unset })
    }

    @Test fun theLastSongHasNothingNextAndBadInputsGiveNothing() {
        assertEquals(emptyList<Int>(), upNextOrder(4, 5) { unset })
        assertEquals(emptyList<Int>(), upNextOrder(-1, 5) { 0 })
        assertEquals(emptyList<Int>(), upNextOrder(0, 0) { 0 })
    }

    @Test fun aLoopingOrOutOfRangeOrderCannotHangTheScreen() {
        assertEquals(listOf(1, 2), upNextOrder(0, 3) { (it + 1) % 3 })   // wraps back to the current song
        assertEquals(listOf(1), upNextOrder(0, 3) { if (it == 0) 1 else 1 }) // repeats itself
        assertEquals(listOf(1), upNextOrder(0, 3) { if (it == 0) 1 else 9 }) // points past the end
    }

    @Test fun unknownLengthsAreMissingNotZero() {
        assertEquals(QueueLength(420_000, complete = true), queueLength(listOf(200_000L, 220_000L)))
        assertEquals(QueueLength(200_000, complete = false), queueLength(listOf(200_000L, null, 0L, -5L)))
        assertEquals(QueueLength(0, complete = true), queueLength(emptyList()))
    }

    @Test fun theSummaryCountsSongsAndSaysWhenTheTotalIsPartial() {
        assertEquals("10 songs, 34 minutes", queueSummary(10, QueueLength(34 * 60_000L, true)))
        assertEquals("1 song, 1 minute", queueSummary(1, QueueLength(40_000, true)))
        assertEquals("25 songs, 1 hour 5 min", queueSummary(25, QueueLength(65 * 60_000L, true)))
        assertEquals("40 songs, 2 hours", queueSummary(40, QueueLength(120 * 60_000L, true)))
        assertEquals("10 songs, at least 30 minutes", queueSummary(10, QueueLength(30 * 60_000L, false)))
        // Nothing known: only the count, never "0 minutes".
        assertEquals("3 songs", queueSummary(3, QueueLength(0, false)))
    }

    @Test fun largePositiveLengthsSaturateWithoutHidingTheLowerBound() {
        assertEquals(QueueLength(Long.MAX_VALUE, true), queueLength(listOf(Long.MAX_VALUE - 1, 1L)))
        assertEquals(QueueLength(Long.MAX_VALUE, false), queueLength(listOf(Long.MAX_VALUE, 1L)))
        assertEquals(QueueLength(Long.MAX_VALUE, false), queueLength(listOf(Long.MAX_VALUE, null, Long.MAX_VALUE)))
        assertEquals("2 songs, at least 2562047788015 hours 13 min",
            queueSummary(2, queueLength(listOf(Long.MAX_VALUE, 1L))))
    }

    @Test fun roundingLargeAcceptedLengthDoesNotWrapToOneMinute() {
        assertEquals("1 song, 2562047788015 hours 13 min", queueSummary(1, QueueLength(Long.MAX_VALUE, true)))
        assertEquals("1 song, 1 minute", queueSummary(1, QueueLength(89_999, true)))
        assertEquals("1 song, 2 minutes", queueSummary(1, QueueLength(90_000, true)))
        assertEquals("0 songs", queueSummary(0, QueueLength(Long.MAX_VALUE, true)))
    }

    @Test fun undoRebuildsTheAddressMuonWouldHaveMade() {
        assertEquals("http://192.168.1.10:7814/api1/file/42", restoreUrl("http://192.168.1.10:7814/42"))
        // Not a media ID Muon makes: no address, so no Undo.
        assertNull(restoreUrl("http://192.168.1.10:7814/abc"))
        assertNull(restoreUrl("http://example.com:7814/42"))
        assertNull(restoreUrl("42"))
        assertNull(restoreUrl("http://192.168.1.10:7814/api1/file/42"))
    }

    @Test fun rowKeysSurviveRemovalsAndTellRepeatsApart() {
        val before = occurrenceKeys(listOf("a", "b", "c", "b"))
        assertEquals(listOf("a#1", "b#1", "c#1", "b#2"), before)
        // Removing "a" leaves every other row with the key it had.
        assertEquals(before.drop(1), occurrenceKeys(listOf("b", "c", "b")))
        assertEquals(before.toSet().size, before.size)
        assertEquals(emptyList<String>(), occurrenceKeys(emptyList()))
    }

    @Test fun theWindowIsAHandfulNotTheWholeLibrary() {
        assertTrue(QUEUE_WINDOW in 20..60)
    }

    @Test fun movingARowShiftsThoseBetween() {
        assertEquals(listOf("b", "c", "a", "d"), listOf("a", "b", "c", "d").moved(0, 2))
        assertEquals(listOf("a", "d", "b", "c"), listOf("a", "b", "c", "d").moved(3, 1))
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(1, 1))
        assertEquals(listOf("a", "b"), listOf("a", "b").moved(0, 5))
    }

    @Test fun aDropBecomesOnePlayerMoveBetweenOriginalIndices() {
        // Now playing is index 4, so Next up shows indices 5, 6, 7, 8.
        val shown = listOf(5, 6, 7, 8)
        // Drag the first shown song below the third: the player moves index 5 to index 7.
        assertEquals(5 to 7, queueMove(shown, 0, 2))
        // And back up: index 8 to index 6.
        assertEquals(8 to 6, queueMove(shown, 3, 1))
        assertNull(queueMove(shown, 2, 2))
        assertNull(queueMove(shown, 0, 9))
    }

    @Test fun aHeldRowScrollsTheListOnlyNearAnEdge() {
        // A 1000 px list with a 100 px edge band, up to 20 px per frame.
        assertEquals(0f, edgeScroll(400f, 480f, 0f, 1000f, 100f, 20f), 0.001f)
        assertEquals(-10f, edgeScroll(50f, 130f, 0f, 1000f, 100f, 20f), 0.001f)
        assertEquals(-20f, edgeScroll(-30f, 50f, 0f, 1000f, 100f, 20f), 0.001f)
        assertEquals(10f, edgeScroll(870f, 950f, 0f, 1000f, 100f, 20f), 0.001f)
        assertEquals(0f, edgeScroll(50f, 130f, 0f, 1000f, 0f, 20f), 0.001f)
    }
}
