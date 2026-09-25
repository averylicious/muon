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

    @Test fun undoRebuildsTheAddressMuonWouldHaveMade() {
        assertEquals("http://192.168.1.10:7814/api1/file/42", restoreUrl("http://192.168.1.10:7814/42"))
        // Not a media ID Muon makes: no address, so no Undo.
        assertNull(restoreUrl("http://192.168.1.10:7814/abc"))
        assertNull(restoreUrl("http://example.com:7814/42"))
        assertNull(restoreUrl("42"))
        assertNull(restoreUrl("http://192.168.1.10:7814/api1/file/42"))
    }
}
