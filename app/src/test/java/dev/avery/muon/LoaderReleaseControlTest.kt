package dev.avery.muon

import androidx.media3.exoplayer.upstream.Loader
import androidx.media3.exoplayer.util.ReleasableExecutor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * #179 characterization, not a production gate. The real pinned Media3 Loader runs on the same
 * single-thread/shutdown executor contract as its default constructor. Only the Loadable is
 * synthetic: it deliberately remains inside load() after cancellation/interrupt until permitted.
 * This is not evidence of a blocked Muon cache/file/network read or removable-card data loss.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LoaderReleaseControlTest {
    private val executor = Executors.newSingleThreadExecutor()
    private val loader = Loader(ReleasableExecutor.from(executor) { it.shutdown() })
    private val loadable = HeldLoadable()
    private val callback = RecordingCallback()
    private val unblockExecutor = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val callbackSawLoadExit = AtomicBoolean(false)
    private var releaseRequested = false

    @After fun tearDown() {
        // Always release both test-owned gates, including after a failed assertion. No dependency
        // (cache/database/source) is disposed while an invocation remains alive in this fixture.
        loadable.proceed.countDown()
        unblockExecutor.countDown()
        if (!releaseRequested) requestRelease()
        assertTrue("The release callback must complete during cleanup", released.await(5, TimeUnit.SECONDS))
        assertTrue("No loading worker may outlive the test", executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    @Test fun releaseReturnsAndCancelsBeforeHeldLoadExitsButReleaseCallbackWaits() {
        loader.startLoading(loadable, callback, 0)
        assertTrue("The actual Loader entered load()", loadable.entered.await(5, TimeUnit.SECONDS))

        requestRelease()

        assertTrue("The cancellation request reached the Loadable", loadable.cancelled.get())
        assertEquals("Released cancellation callback is synchronous", 1, callback.cancelled.get())
        assertTrue(callback.releasedCancellation.get())
        assertFalse("isLoading is cleared even though load() is still running", loader.isLoading)
        assertTrue("Executor shutdown is an admission signal", executor.isShutdown)
        assertFalse("Shutdown is not termination", executor.isTerminated)
        assertEquals("load() has not exited", 1L, loadable.exited.count)
        assertEquals("ReleaseCallback cannot overtake the held load on a serial executor", 1L, released.count)
        assertTrue("Cancellation interrupted the invocation without draining it",
            loadable.interrupted.await(5, TimeUnit.SECONDS))

        loadable.proceed.countDown()
        assertTrue("ReleaseCallback runs after the task exits", released.await(5, TimeUnit.SECONDS))
        assertTrue("The callback observed load()'s finally", callbackSawLoadExit.get())
        assertEquals(0, callback.completed.get())
        assertEquals(0, callback.errors.get())
    }

    @Test fun releaseOfExecutorQueuedLoadCancelsWithoutEnteringLoadAndStillAcknowledges() {
        val executorBlocked = CountDownLatch(1)
        executor.execute {
            executorBlocked.countDown()
            awaitThroughInterrupt(unblockExecutor, null)
        }
        assertTrue("The serial executor is held before Loader submission", executorBlocked.await(5, TimeUnit.SECONDS))
        loader.startLoading(loadable, callback, 0)

        requestRelease()

        assertTrue(loadable.cancelled.get())
        assertEquals(1, callback.cancelled.get())
        assertTrue(callback.releasedCancellation.get())
        assertFalse(loader.isLoading)
        assertEquals("The queued task has never entered load()", 1L, loadable.entered.count)
        assertEquals("Callback stays behind previously queued work", 1L, released.count)

        unblockExecutor.countDown()
        assertTrue("Queued cancellation still reaches ReleaseCallback", released.await(5, TimeUnit.SECONDS))
        assertEquals("Canceled queued task must not invoke load()", 1L, loadable.entered.count)
        assertEquals("No load() invocation means no load finally to observe", 1L, loadable.exited.count)
        assertFalse(callbackSawLoadExit.get())
        assertEquals(0, callback.completed.get())
        assertEquals(0, callback.errors.get())
    }

    private fun requestRelease() {
        releaseRequested = true
        loader.release {
            callbackSawLoadExit.set(loadable.exited.count == 0L)
            released.countDown()
        }
    }

    private class HeldLoadable : Loader.Loadable {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        override fun cancelLoad() { cancelled.set(true) }
        override fun load() {
            entered.countDown()
            try { awaitThroughInterrupt(proceed, interrupted) }
            finally { exited.countDown() }
        }
    }

    private class RecordingCallback : Loader.Callback<HeldLoadable> {
        val completed = AtomicInteger()
        val cancelled = AtomicInteger()
        val errors = AtomicInteger()
        val releasedCancellation = AtomicBoolean(false)
        override fun onLoadCompleted(loadable: HeldLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long) {
            completed.incrementAndGet()
        }
        override fun onLoadCanceled(loadable: HeldLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long, released: Boolean) {
            releasedCancellation.set(released)
            cancelled.incrementAndGet()
        }
        override fun onLoadError(loadable: HeldLoadable, elapsedRealtimeMs: Long, loadDurationMs: Long,
            error: IOException, errorCount: Int): Loader.LoadErrorAction {
            errors.incrementAndGet()
            return Loader.DONT_RETRY_FATAL
        }
    }

    companion object {
        private fun awaitThroughInterrupt(gate: CountDownLatch, interrupted: CountDownLatch?) {
            // Deterministic test-owned blocker. The test and teardown release it; interrupt alone
            // intentionally does not stand in for a completed read/close acknowledgment.
            while (true) {
                try { gate.await(); return }
                catch (_: InterruptedException) { interrupted?.countDown() }
            }
        }
    }
}
