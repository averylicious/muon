package dev.avery.muon

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual pinned Material state/mutex and coroutine lifecycle; no Compose rendering, phone or player. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class QueueNoticesTest {
    @Test fun nativeBacklogControlAndBurstReplacementKeepOnlyTheNewestPendingNotice() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val raw = SnackbarHostState()
            val first = owner.async(start = CoroutineStart.UNDISPATCHED) { raw.showSnackbar("first") }
            val second = owner.async(start = CoroutineStart.UNDISPATCHED) { raw.showSnackbar("stale second") }
            raw.currentSnackbarData!!.dismiss()
            runCurrent()
            assertEquals(SnackbarResult.Dismissed, first.await())
            assertEquals("stale second", raw.currentSnackbarData!!.visuals.message)
            second.cancel(); runCurrent(); assertNull(raw.currentSnackbarData)

            val host = SnackbarHostState()
            val notices = QueueNotices(owner, host)
            var released = 0
            val waiters = (0 until 50).map { n ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    try { notices.show("removal $n", "Undo") } finally { released++ }
                }
            }
            runCurrent()
            assertEquals("removal 49", host.currentSnackbarData!!.visuals.message)
            assertEquals(49, released)
            waiters.dropLast(1).forEach { assertEquals(SnackbarResult.Dismissed, it.await()) }
            assertFalse(waiters.last().isCompleted)
            host.currentSnackbarData!!.dismiss(); runCurrent()
            assertEquals(SnackbarResult.Dismissed, waiters.last().await())
            assertEquals(50, released)
            assertNull(host.currentSnackbarData) // No stale notice appears after the newest closes.
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun anUndoTapAlreadyChosenSurvivesReplacementAndItsOperationKeepsRunning() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            // The default raw-data host misses the action receipt. This is the former player-
            // overlay wiring: a same-turn replacement loses its already-resumed native action.
            val rawHost = SnackbarHostState()
            val rawNotices = QueueNotices(owner, rawHost)
            val unobserved = async(start = CoroutineStart.UNDISPATCHED) { rawNotices.show("raw", "Undo") }
            runCurrent()
            rawHost.currentSnackbarData!!.performAction()
            val rawNext = async(start = CoroutineStart.UNDISPATCHED) { rawNotices.show("raw replacement") }
            runCurrent()
            assertEquals(SnackbarResult.Dismissed, unobserved.await())
            rawHost.currentSnackbarData!!.dismiss(); runCurrent()
            assertEquals(SnackbarResult.Dismissed, rawNext.await())
            assertNull(rawHost.currentSnackbarData)

            val host = SnackbarHostState()
            val notices = QueueNotices(owner, host)
            val operation = CompletableDeferred<Unit>()
            var started = false
            var finished = false
            val old = async(start = CoroutineStart.UNDISPATCHED) {
                if (notices.show("old", "Undo") == SnackbarResult.ActionPerformed) {
                    started = true
                    operation.await() // The caller, not the display worker, owns acknowledged Undo.
                    finished = true
                }
            }
            runCurrent()
            // Replace before the host continuation gets its next dispatcher turn. Cancelling the
            // native host coroutine here would lose the action via prompt cancellation.
            notices.observeAction(host.currentSnackbarData!!).performAction()
            val latest = async(start = CoroutineStart.UNDISPATCHED) { notices.show("new refusal") }
            runCurrent()
            assertTrue(started); assertFalse(finished); assertFalse(old.isCompleted)
            assertEquals("new refusal", host.currentSnackbarData!!.visuals.message)
            operation.complete(Unit); runCurrent(); old.await(); assertTrue(finished)
            host.currentSnackbarData!!.dismiss(); runCurrent()
            assertEquals(SnackbarResult.Dismissed, latest.await())
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun aQueueWaiterBehindAnUnrelatedHostCallerCanStillBeReplaced() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val host = SnackbarHostState()
            val notices = QueueNotices(owner, host)
            val first = async(start = CoroutineStart.UNDISPATCHED) { notices.show("first", "Undo") }
            runCurrent()
            val unrelated = owner.async(start = CoroutineStart.UNDISPATCHED) {
                host.showSnackbar("storage notice", "Settings")
            }
            val obsolete = async(start = CoroutineStart.UNDISPATCHED) { notices.show("obsolete queue", "Undo") }
            runCurrent()
            assertEquals("storage notice", host.currentSnackbarData!!.visuals.message)
            assertFalse(obsolete.isCompleted) // Native mutex waiter, not the visible queue message.
            val latest = async(start = CoroutineStart.UNDISPATCHED) { notices.show("newest queue", "Undo") }
            runCurrent()
            assertEquals("newest queue", host.currentSnackbarData!!.visuals.message)
            assertEquals(SnackbarResult.Dismissed, first.await())
            assertEquals(SnackbarResult.Dismissed, obsolete.await())
            assertEquals(SnackbarResult.Dismissed, unrelated.await())
            host.currentSnackbarData!!.dismiss(); runCurrent()
            assertEquals(SnackbarResult.Dismissed, latest.await())
            assertNull(host.currentSnackbarData)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun immediateDispatchDoesNotDismissItsOwnNewNotice() = runTest {
        val owner = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val host = SnackbarHostState()
            val notices = QueueNotices(owner, host)
            val shown = async(start = CoroutineStart.UNDISPATCHED) { notices.show("inline display", "Undo") }
            assertEquals("inline display", host.currentSnackbarData!!.visuals.message)
            assertFalse(shown.isCompleted)
            host.currentSnackbarData!!.dismiss(); runCurrent()
            assertEquals(SnackbarResult.Dismissed, shown.await())
            assertNull(host.currentSnackbarData)
        } finally { owner.cancel(); runCurrent() }
    }

    @Test fun cancellingACallerOrTheScreenClearsItsNoticeAndPendingDisplay() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val host = SnackbarHostState()
        val notices = QueueNotices(owner, host)
        try {
            val caller = async(start = CoroutineStart.UNDISPATCHED) { notices.show("cancelled caller", "Undo") }
            runCurrent(); assertNotNull(host.currentSnackbarData)
            caller.cancel(); runCurrent(); assertNull(host.currentSnackbarData)
            assertTrue(caller.isCancelled)
            val active = async(start = CoroutineStart.UNDISPATCHED) { notices.show("active") }
            runCurrent()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { notices.show("pending") }
            // Dispose while the old host continuation and newest display waiter are both unsettled.
            owner.cancel(); runCurrent()
            assertTrue(active.isCompleted); assertTrue(pending.isCompleted)
            assertNull(host.currentSnackbarData)
            assertEquals(SnackbarResult.Dismissed, notices.show("after disposal"))
        } finally { owner.cancel(); runCurrent() }
    }
}
