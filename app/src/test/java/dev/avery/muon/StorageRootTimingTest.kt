package dev.avery.muon

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StorageRootTimingTest {
    @Test fun disabledFactoryNeverCallsClockOrSink() {
        assertNull(StorageRootTiming.begin(false,AtomicInteger(32),{error("Disabled clock")},{error("Disabled sink")}))
    }
    @Test fun cappedCumulativeCountersStopClockAndLogWorkWithoutStoppingActions() {
        var time=0L;var clocks=0;var actions=0;val events=mutableListOf<StorageRootTiming.Event>()
        val timing=requireNotNull(StorageRootTiming.begin(true,AtomicInteger(2),{clocks++;time},events::add))
        repeat(1000) {timing.measure(StorageRootTiming.Phase.CARD_VOLUME) {actions++;time+=3;actions}}
        assertEquals(1000,actions);assertEquals(2,events.size);assertEquals(128,clocks)
        assertEquals(1L,events[0].count);assertEquals(3L,events[0].totalMs)
        assertEquals(64L,events[1].count);assertEquals(192L,events[1].totalMs);assertEquals(0L,events[1].failures)
    }
    @Test fun failuresAndBrokenDiagnosticsPreserveExactOriginalOutcome() {
        val refusal=IOException("Disposable refusal");val events=mutableListOf<StorageRootTiming.Event>();var time=0L
        val timing=requireNotNull(StorageRootTiming.begin(true,AtomicInteger(4),{time++},events::add))
        assertSame(refusal,assertThrows(IOException::class.java) {timing.measure(StorageRootTiming.Phase.PHONE_ROOT) {throw refusal}})
        assertEquals(1L,events.single().failures)
        val broken=requireNotNull(StorageRootTiming.begin(true,AtomicInteger(4),{throw IOException("Clock unavailable")},{error("Unexpected sink")}))
        assertEquals(7,broken.measure(StorageRootTiming.Phase.PHONE_ROOT) {7})
        assertSame(refusal,assertThrows(IOException::class.java) {broken.measure(StorageRootTiming.Phase.PHONE_ROOT) {throw refusal}})
        val badSink=requireNotNull(StorageRootTiming.begin(true,AtomicInteger(4),{0L},{throw IOException("Sink unavailable")}))
        assertSame(refusal,assertThrows(IOException::class.java) {badSink.measure(StorageRootTiming.Phase.CARD_ROOT) {throw refusal}})
    }
    @Test fun concurrentActionsAreNotSerializedAndProcessEventBudgetIsShared() {
        val allowance=AtomicInteger(1);val events=java.util.Collections.synchronizedList(mutableListOf<StorageRootTiming.Event>())
        val first=requireNotNull(StorageRootTiming.begin(true,allowance,{0L},events::add))
        val second=requireNotNull(StorageRootTiming.begin(true,allowance,{0L},events::add))
        val entered=java.util.concurrent.CountDownLatch(2);val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs=listOf(StorageRootTiming.Phase.PHONE_ROOT,StorageRootTiming.Phase.CARD_ROOT).map {phase ->pool.submit<Int> {
                first.measure(phase) {entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS));42}
            }}
            jobs.forEach {assertEquals(42,it.get(10,TimeUnit.SECONDS).toInt())}
            assertEquals(42,second.measure(StorageRootTiming.Phase.CARD_VOLUME) {42})
            assertEquals(1,events.size);assertEquals(0,allowance.get())
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS))}
    }
}
