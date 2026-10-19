package dev.avery.muon

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MoveOperationTimingTest {
    @Test fun durableBoundariesPartitionMultipleCopiesWithoutRetainingTheirIdentity() {
        var now=10L;val events=mutableListOf<MoveOperationTiming.Event>()
        val timing=requireNotNull(MoveOperationTiming.begin(true,AtomicInteger(1),{now},events::add))
        now=12;timing.phase(MoveOperationTiming.Phase.ADMISSION)
        repeat(2) {
            now+=3;timing.phase(MoveOperationTiming.Phase.COPY)
            now+=5;timing.boundary(PartitionOriginalMoveStage.Copied)
            now+=7;timing.boundary(PartitionOriginalMoveStage.Published)
            now+=11;timing.boundary(PartitionOriginalMoveStage.Pending)
            now+=13;timing.phase(MoveOperationTiming.Phase.SUBMISSION)
            now+=17;timing.phase(MoveOperationTiming.Phase.ADMISSION)
        }
        timing.finish(true)
        val e=events.single();assertTrue(e.complete)
        assertEquals(114L,e.totalMs);assertEquals(2L,e.selectionMs);assertEquals(6L,e.admissionMs)
        assertEquals(10L,e.copyMs);assertEquals(14L,e.publishMs);assertEquals(22L,e.coverMs)
        assertEquals(26L,e.retireMs);assertEquals(34L,e.submissionMs)
        timing.finish(false);timing.boundary(PartitionOriginalMoveStage.Complete)
        assertEquals(1,events.size)
    }
    @Test fun disabledAndExhaustedBudgetsDoNotInvokeClockOrSink() {
        val budget=AtomicInteger(1)
        assertNull(MoveOperationTiming.begin(false,budget,{error("Disabled clock")},{error("Disabled sink")}))
        assertEquals(1,budget.get())
        assertNotNull(MoveOperationTiming.begin(true,budget,{0L},{}))
        assertEquals(0,budget.get())
        assertNull(MoveOperationTiming.begin(true,budget,{error("Exhausted clock")},{error("Exhausted sink")}))
    }
    @Test fun clockAndSinkFailurePreserveTheOriginalExceptionAndBackwardClockDoesNotSubtractTime() {
        assertNull(MoveOperationTiming.begin(true,AtomicInteger(1),{throw IOException("Clock failed")},{}))
        var now=10L;var failed=false;var event:MoveOperationTiming.Event?=null
        val timing=requireNotNull(MoveOperationTiming.begin(true,AtomicInteger(1),{if(failed) throw IOException("Clock failed");now},{event=it}))
        now=20;timing.phase(MoveOperationTiming.Phase.COPY)
        failed=true;timing.boundary(PartitionOriginalMoveStage.Copied);timing.finish(false)
        assertNull(event)
        now=10
        val sink=requireNotNull(MoveOperationTiming.begin(true,AtomicInteger(1),{now},{event=it;throw IOException("Log failed")}))
        now=20;sink.phase(MoveOperationTiming.Phase.COPY)
        now=5;sink.phase(MoveOperationTiming.Phase.PUBLISH)
        now=25
        val actual=IOException("Original storage failure")
        assertSame(actual,assertThrows(IOException::class.java) {try {throw actual} finally {sink.finish(false)}})
        val e=requireNotNull(event);assertFalse(e.complete)
        assertEquals(15L,e.totalMs);assertEquals(10L,e.selectionMs);assertEquals(0L,e.copyMs);assertEquals(5L,e.publishMs)
    }
}
