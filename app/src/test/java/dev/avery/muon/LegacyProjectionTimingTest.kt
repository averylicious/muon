package dev.avery.muon

import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LegacyProjectionTimingTest {
    @Test fun disabledTraceDoesNotTouchAllowanceClockOrSink() {
        val allowance=AtomicInteger(2)
        repeat(100) {assertNull(LegacyProjectionTiming.begin(false,allowance=allowance,
            clock={error("Disabled clock")},emit={error("Disabled sink")}))}
        assertEquals(2,allowance.get())
    }
    @Test fun phasesAndOverlappingFreshChecksEmitOnlyOneConstantAggregate() {
        var at=0L;val events=mutableListOf<LegacyProjectionTiming.Event>()
        val trace=requireNotNull(LegacyProjectionTiming.begin(true,true,true,AtomicInteger(1),{at},events::add))
        at=10;trace.phase(LegacyProjectionTiming.Phase.METADATA)
        trace.check {at=13};at=20;trace.phase(LegacyProjectionTiming.Phase.FILES)
        trace.check {at=25};at=30;trace.phase(LegacyProjectionTiming.Phase.CENSUS)
        at=34;trace.finish(true);trace.finish(false)
        assertEquals(listOf(LegacyProjectionTiming.Event(true,true,true,34,10,10,10,4,2,8)),events)
        assertTrue(LegacyProjectionTiming.Event::class.java.declaredFields.none {it.type==String::class.java})
    }
    @Test fun budgetSaturatesWithoutQueueOrHistoryEvenWhenSinkThrows() {
        val allowance=AtomicInteger(2);var clocks=0;var sinks=0
        repeat(50) {
            LegacyProjectionTiming.begin(true,allowance=allowance,clock={clocks++;0},emit={sinks++;error("Sink failed")})?.finish(true)
        }
        assertEquals(0,allowance.get());assertEquals(2,sinks);assertEquals(4,clocks)
    }
    @Test fun cancellationAndDiagnosticFailuresDoNotReplaceActualFailure() {
        var clockWorks=true;var at=10L;val events=mutableListOf<LegacyProjectionTiming.Event>()
        val trace=requireNotNull(LegacyProjectionTiming.begin(true,allowance=AtomicInteger(1),
            clock={if(clockWorks) at else error("Clock failed")},emit=events::add))
        trace.phase(LegacyProjectionTiming.Phase.METADATA)
        val failure=InterruptedIOException("Actual cancellation")
        assertSame(failure,assertThrows(InterruptedIOException::class.java) {trace.check {clockWorks=false;throw failure}})
        trace.finish(false)
        assertEquals(1,events.size);assertFalse(events.single().successful)
        assertEquals(1L,events.single().postSchemaCheckCount)
        assertEquals(0L,events.single().postSchemaCheckMs)
    }
}
