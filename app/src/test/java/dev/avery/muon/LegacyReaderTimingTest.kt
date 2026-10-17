package dev.avery.muon

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LegacyReaderTimingTest {
    @Test fun disabledAndExhaustedNeverCallClockOrSink() {
        val readers=AtomicInteger(2)
        assertNull(LegacyReaderTiming.begin(false,readers,AtomicInteger(2),{error("Disabled clock")},{error("Disabled sink")}))
        assertEquals(2,readers.get())
        assertNull(LegacyReaderTiming.begin(true,AtomicInteger(0),AtomicInteger(2),{error("No reader clock")},{error("No sink")}))
        assertNull(LegacyReaderTiming.begin(true,readers,AtomicInteger(0),{error("No event clock")},{error("No sink")}))
    }
    @Test fun cumulativeProgressAndKnownCloseKeepSeparateStageDurationsAndByteCounts() {
        var now=100L;val events=mutableListOf<LegacyReaderTiming.Event>()
        val t=requireNotNull(LegacyReaderTiming.begin(true,AtomicInteger(1),AtomicInteger(8),{now},events::add))
        t.measure(LegacyReaderTiming.Stage.PROJECTION) {now+=7}
        assertEquals(19,t.measure(LegacyReaderTiming.Stage.OPEN) {now+=11;19});t.opened()
        t.measure(LegacyReaderTiming.Stage.CHECKS) {now+=3}
        for((cost,count) in listOf(5L to 4,9L to 3,10L to 2)) {
            t.measure(LegacyReaderTiming.Stage.READ) {now+=cost};t.read(count)
        }
        assertEquals(3,events.size) // Open, read1, read2; read3 is not another progress event.
        t.measure(LegacyReaderTiming.Stage.CLOSE) {now+=2};t.finish(true)
        val e=events.last();assertTrue(e.closed);assertFalse(e.failed);assertEquals(47L,e.totalMs)
        assertEquals(7L,e.projectionMs);assertEquals(11L,e.openMs);assertEquals(3L,e.checksMs)
        assertEquals(24L,e.readMs);assertEquals(2L,e.closeMs);assertEquals(3L,e.readCalls);assertEquals(9L,e.bytesRead)
        t.finish(false);assertEquals(4,events.size)
    }
    @Test fun clockAndSinkFailuresCannotReplaceActualWorkOrIoFailure() {
        assertNull(LegacyReaderTiming.begin(true,AtomicInteger(1),AtomicInteger(2),{throw IOException("Clock failed")},{error("No sink")}))
        var clocks=0
        val t=requireNotNull(LegacyReaderTiming.begin(true,AtomicInteger(1),AtomicInteger(2),{if(++clocks>1) throw IOException("Clock failed");0L},{error("No sink")}))
        assertEquals(7,t.measure(LegacyReaderTiming.Stage.OPEN) {7});t.opened();t.finish(true)
        val events=mutableListOf<LegacyReaderTiming.Event>()
        val sink=requireNotNull(LegacyReaderTiming.begin(true,AtomicInteger(1),AtomicInteger(8),{0L},{events+=it;throw IOException("Sink failed")}))
        sink.opened();val actual=IOException("Actual I/O failure")
        assertSame(actual,assertThrows(IOException::class.java) {sink.measure(LegacyReaderTiming.Stage.READ) {throw actual}})
        sink.failure();sink.finish(false)
        assertTrue(events.last().failed);assertFalse(events.last().closed)
    }
    @Test fun sharedEventCapStopsTimingWorkButNeverStopsRealWork() {
        var clocks=0;val budget=AtomicInteger(3);val readers=AtomicInteger(2);val events=mutableListOf<LegacyReaderTiming.Event>()
        val a=requireNotNull(LegacyReaderTiming.begin(true,readers,budget,{clocks++;0L},events::add))
        val b=requireNotNull(LegacyReaderTiming.begin(true,readers,budget,{clocks++;0L},events::add))
        a.opened();b.opened();a.read(1);assertEquals(0,budget.get());val before=clocks
        var work=0
        repeat(100) {a.measure(LegacyReaderTiming.Stage.READ) {work++};a.read(1);b.read(-1)}
        a.finish(true);b.finish(true)
        assertEquals(100,work);assertEquals(before,clocks);assertEquals(3,events.size)
        assertNull(LegacyReaderTiming.begin(true,AtomicInteger(1),budget,{error("No clock")},{error("No sink")}))
    }
    @Test fun concurrentReadersReceiveDistinctProcessLocalNumbersWithinSharedCaps() {
        val readers=AtomicInteger(32);val budget=AtomicInteger(32)
        val events=java.util.Collections.synchronizedList(mutableListOf<LegacyReaderTiming.Event>())
        val workers=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks=(0 until 64).map {workers.submit {
                LegacyReaderTiming.begin(true,readers,budget,{0L},events::add)?.opened()
            }}
            tasks.forEach {it.get(10,java.util.concurrent.TimeUnit.SECONDS)}
            assertEquals(32,events.size);assertEquals((1..32).toSet(),events.map {it.reader}.toSet())
            assertEquals(0,readers.get());assertEquals(0,budget.get())
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))}
    }
    @Test fun nestedElapsedCountersSaturateAndEofDoesNotCountAsNegativeBytes() {
        var now=0L;val events=mutableListOf<LegacyReaderTiming.Event>()
        val t=requireNotNull(LegacyReaderTiming.begin(true,AtomicInteger(1),AtomicInteger(8),{now},events::add))
        t.measure(LegacyReaderTiming.Stage.READ) {t.measure(LegacyReaderTiming.Stage.READ) {now=Long.MAX_VALUE}}
        t.read(Int.MAX_VALUE);t.read(Int.MAX_VALUE);t.read(-1);t.finish(true)
        val e=events.last();assertEquals(Long.MAX_VALUE,e.readMs);assertEquals(3L,e.readCalls)
        assertEquals(2L*Int.MAX_VALUE,e.bytesRead);assertTrue(e.closed)
    }
}
