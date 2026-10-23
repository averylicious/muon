package dev.avery.muon

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class MoveVerificationTimingTest {
    @Test fun disabledAndExhaustedNeverReadClockOrEmit() {
        val budget=AtomicInteger(2)
        assertNull(MoveVerificationTiming.begin(false,budget,{error("Disabled clock")},{error("Disabled sink")}))
        assertEquals(2,budget.get())
        assertNull(MoveVerificationTiming.begin(true,AtomicInteger(0),{error("Exhausted clock")},{error("Exhausted sink")}))
    }
    @Test fun fixedPhasesPartitionElapsedTimeAndFinishOnlyOnce() {
        var now=100L;var clocks=0;val events=mutableListOf<MoveVerificationTiming.Event>()
        val timing=requireNotNull(MoveVerificationTiming.begin(true,AtomicInteger(1),{clocks++;now},events::add))
        now=105;timing.phase(MoveVerificationTiming.Phase.OPEN)
        now=117;timing.phase(MoveVerificationTiming.Phase.AUDIO)
        now=122;timing.phase(MoveVerificationTiming.Phase.CLOSE)
        now=130;timing.finish(true)
        val e=events.single();assertTrue(e.successful);assertEquals(30L,e.totalMs)
        assertEquals(5L,e.admissionMs);assertEquals(12L,e.openMs);assertEquals(5L,e.audioMs);assertEquals(8L,e.closeMs)
        assertEquals(0L,e.metadataMs+e.afterMs+e.finalMs)
        val before=clocks;timing.finish(false);timing.phase(MoveVerificationTiming.Phase.FINAL)
        assertEquals(before,clocks);assertEquals(1,events.size)
    }
    @Test fun clockAndSinkFailuresCannotReplaceTheActualIoFailure() {
        assertNull(MoveVerificationTiming.begin(true,AtomicInteger(1),{throw IOException("Clock failed")},{error("No event")}))
        var clocks=0
        val failed=requireNotNull(MoveVerificationTiming.begin(true,AtomicInteger(1),{if(++clocks>1) throw IOException("Clock failed");0L},{error("No event")}))
        failed.phase(MoveVerificationTiming.Phase.OPEN);failed.finish(false);assertEquals(2,clocks)
        var unsuccessful=false
        val sink=requireNotNull(MoveVerificationTiming.begin(true,AtomicInteger(1),{0L},{e ->unsuccessful=!e.successful;throw IOException("Sink failed")}))
        val actual=IOException("Actual I/O failed")
        assertSame(actual,assertThrows(IOException::class.java) {try {throw actual} finally {sink.finish(false)}})
        assertTrue(unsuccessful)
    }
    @Test fun attemptedObservationBudgetIsSharedAndPhaseCountersSaturate() {
        val budget=AtomicInteger(1);var now=0L;val events=mutableListOf<MoveVerificationTiming.Event>()
        val timing=requireNotNull(MoveVerificationTiming.begin(true,budget,{now},events::add))
        assertNull(MoveVerificationTiming.begin(true,budget,{error("No further clock")},{error("No extra event")}))
        timing.phase(MoveVerificationTiming.Phase.AUDIO)
        now=Long.MAX_VALUE;timing.phase(MoveVerificationTiming.Phase.AUDIO)
        now=0;timing.phase(MoveVerificationTiming.Phase.AUDIO)
        now=Long.MAX_VALUE;timing.finish(true)
        assertEquals(Long.MAX_VALUE,events.single().audioMs);assertEquals(0,budget.get())
    }
}
