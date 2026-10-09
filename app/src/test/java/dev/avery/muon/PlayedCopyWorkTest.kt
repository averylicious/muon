package dev.avery.muon

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class PlayedCopyWorkTest {
    private class ManualWorker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun next() { tasks.removeFirst().run() }
    }
    private lateinit var worker: ManualWorker
    private lateinit var timer: ScheduledThreadPoolExecutor
    private lateinit var work: PlayedCopyWork

    @Before fun setUp() {
        worker = ManualWorker()
        timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        work = PlayedCopyWork(worker, timer)
    }
    @After fun tearDown() {
        timer.shutdownNow()
        assertTrue(timer.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun onlyTheLatestWaitingSongIsRetainedAndOnlyOneDrainIsSubmitted() {
        val seen = ArrayList<String>()
        repeat(100) { n -> work.copy("song$n") { seen += "song$n" } }
        assertEquals(1, worker.tasks.size)
        worker.next()
        assertEquals(listOf("song99"), seen)
        assertTrue(worker.tasks.isEmpty())
        assertTrue("Completed jobs do not retain timer closures", timer.queue.isEmpty())
    }

    @Test fun duplicateOfActiveCopyDoesNotReplaceTheNextDistinctSong() {
        val seen = ArrayList<String>()
        work.copy("active") {
            seen += "active"
            work.copy("next") { seen += "next" }
            work.copy("active") { fail("Active duplicate must not run") }
        }
        worker.next()
        assertEquals(listOf("active", "next"), seen)
    }

    @Test fun maintenanceIsCoalescedAndRunsBeforeLaterExplicitOptionalCopy() {
        val seen = ArrayList<String>()
        work.copy("old") { fail("Clear must discard old waiting copies") }
        work.resize { seen += "resize1" }
        work.resize { seen += "resize2" }
        work.clear { seen += "clear1" }
        work.clear { seen += "clear2" }
        work.copy("fresh") { seen += "fresh" }
        worker.next()
        assertEquals(listOf("clear2", "resize2", "fresh"), seen)
    }

    @Test fun clearCancelsActiveCopyAndAllowsANewRequestForTheSameSong() {
        val seen = ArrayList<String>()
        work.copy("same") { token ->
            token.onCancel { seen += "cancel" }
            work.copy("old-next") { fail("Old pending copy must be discarded") }
            work.clear { seen += "clear" }
            assertTrue(token.isCancelled)
            work.copy("same") { seen += "fresh" }
        }
        worker.next()
        assertEquals(listOf("cancel", "clear", "fresh"), seen)
    }

    @Test fun resizeCancelsActiveCopyButKeepsTheNextWaitingSong() {
        val seen = ArrayList<String>()
        work.copy("active") { token ->
            token.onCancel { seen += "cancel" }
            work.copy("next") { seen += "next" }
            work.resize { seen += "resize" }
            assertTrue(token.isCancelled)
        }
        worker.next()
        assertEquals(listOf("cancel", "resize", "next"), seen)
    }

    @Test fun aCompletedSongCanBeRequestedAgainWithoutPermanentTombstones() {
        var count = 0
        work.copy("same") { count++ }
        worker.next()
        work.copy("same") { count++ }
        worker.next()
        assertEquals(2, count)
    }

    @Test fun failureDoesNotLeaveMaintenanceStrandedBehindABusyFlag() {
        var cleared = false
        work.copy("failing") {
            work.clear { cleared = true }
            throw IllegalStateException("Fixture failure")
        }
        assertThrows(IllegalStateException::class.java) { worker.next() }
        assertEquals(1, worker.tasks.size)
        worker.next()
        assertTrue(cleared)
        assertTrue(worker.tasks.isEmpty())
    }

    @Test fun unknownSourceOwnershipDiscardsQueuedWorkAndNeverRunsMaintenanceOrRetry() {
        val exact=Any()
        work.copy("active") {
            work.copy("waiting") { fail("Unknown source must refuse next copy") }
            work.clear { fail("Unknown source must refuse clear") }
            work.resize { fail("Unknown source must refuse resize") }
            work.quarantine(exact)
        }
        worker.next()
        assertTrue(work.isUncertain); assertTrue(worker.tasks.isEmpty())
        work.quarantine(Any())
        assertSame(exact,PlayedCopyWork::class.java.getDeclaredField("retained").apply { isAccessible=true }.get(work))
        work.copy("retry") { fail("No retry") }; work.clear { fail("No clear") }; work.resize { fail("No resize") }
        assertTrue(worker.tasks.isEmpty()); assertTrue(timer.queue.isEmpty())
    }

    @Test fun cancellationIsIdempotentAndCancelsLateInstalledOperation() {
        val token = PlayedCopyCancellation()
        token.cancel()
        var count = 0
        token.onCancel { count++ }
        token.cancel()
        token.cancel()
        assertTrue(token.isCancelled)
        assertEquals(1, count)
    }

    @Test fun deadlineCancelsARunningJobAndWorkerAcceptsSubsequentWork() {
        val realWorker = Executors.newSingleThreadExecutor()
        try {
            val bounded = PlayedCopyWork(realWorker, timer, lifetimeMillis = 1000)
            val entered = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            bounded.copy("deadline") { token ->
                token.onCancel { cancelled.countDown() }
                entered.countDown()
                check(cancelled.await(5, TimeUnit.SECONDS)) { "Deadline did not cancel the job" }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            realWorker.submit {}.get(5, TimeUnit.SECONDS)
            val next = CountDownLatch(1)
            bounded.copy("next") { next.countDown() }
            assertTrue(next.await(5, TimeUnit.SECONDS))
            realWorker.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue(timer.queue.isEmpty())
        } finally {
            realWorker.shutdownNow()
            assertTrue(realWorker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
