package dev.avery.muon

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SavedDigestTimingTest {
    @Test fun disabledExhaustedAndFailedStartsDoNotKeepAttemptingClocks() {
        val budget = AtomicInteger(1)
        assertNull(SavedDigestTiming.begin(false, budget, { error("Disabled clock") }))
        assertEquals(1, budget.get())
        assertNull(SavedDigestTiming.begin(true, budget, { throw IOException("Clock") }))
        assertEquals(0, budget.get())
        assertNull(SavedDigestTiming.begin(true, budget, { error("Exhausted clock") }))
    }
    @Test fun disjointStagesCountOnlyTheirWorkAndFinishOnce() {
        var now = 100L
        var clocks = 0
        val events = mutableListOf<SavedDigestTiming.Event>()
        val timing = requireNotNull(SavedDigestTiming.begin(true, AtomicInteger(1), { clocks++; now }, events::add))
        timing.measure(SavedDigestTiming.Stage.BINDING) { now += 5 }
        timing.measure(SavedDigestTiming.Stage.READ) { now += 13 }
        timing.measure(SavedDigestTiming.Stage.CLOSE) { now += 7 }
        now += 2
        timing.finish(true)
        val e = events.single()
        assertTrue(e.successful)
        assertEquals(27L, e.totalMs)
        assertEquals(5L, e.bindingMs)
        assertEquals(13L, e.readMs)
        assertEquals(7L, e.closeMs)
        assertEquals(0L, e.hashMs + e.openMs + e.createMs + e.checkpointMs)
        val before = clocks
        timing.finish(false)
        assertEquals(42, timing.measure(SavedDigestTiming.Stage.READ) { 42 })
        assertEquals(before, clocks)
        assertEquals(1, events.size)
    }
    @Test fun diagnosticClockAndSinkFailuresPreserveTheActualActionAndException() {
        var clocks = 0
        val clock = requireNotNull(SavedDigestTiming.begin(true, AtomicInteger(1), {
            if (++clocks > 1) throw IOException("Clock failed")
            0L
        }, { error("No valid event") }))
        var actions = 0
        val actual = IOException("Actual read failed")
        assertSame(actual, assertThrows(IOException::class.java) {
            clock.measure(SavedDigestTiming.Stage.READ) { actions++; throw actual }
        })
        clock.finish(false)
        assertEquals(1, actions)
        assertEquals(2, clocks)
        var reportedFailure = false
        val sink = requireNotNull(SavedDigestTiming.begin(true, AtomicInteger(1), { 0L }, {
            reportedFailure = !it.successful
            throw IOException("Sink failed")
        }))
        assertSame(actual, assertThrows(IOException::class.java) {
            try { sink.measure(SavedDigestTiming.Stage.READ) { throw actual } }
            finally { sink.finish(false) }
        })
        assertTrue(reportedFailure)
    }
    @Test fun backwardClockCannotMakeNegativeDurationsOrOverflowAccumulation() {
        var now = 10L
        val events = mutableListOf<SavedDigestTiming.Event>()
        val timing = requireNotNull(SavedDigestTiming.begin(true, AtomicInteger(1), { now }, events::add))
        timing.measure(SavedDigestTiming.Stage.HASH) { now = Long.MAX_VALUE }
        timing.measure(SavedDigestTiming.Stage.HASH) { now = 0 }
        timing.finish(true)
        assertEquals(Long.MAX_VALUE - 10, events.single().hashMs)
        assertEquals(Long.MAX_VALUE - 10, events.single().totalMs)
    }
}
