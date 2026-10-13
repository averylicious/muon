package dev.avery.muon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SheetActionTest {
    @Test fun successfulHideDismissesThenActsOnce() = runBlocking {
        val events = mutableListOf<String>()
        completeSheetAction({ events += "hidden" }, { true }, { events += "dismiss" }, { events += "action" })
        assertEquals(listOf("hidden", "dismiss", "action"), events)
    }

    @Test fun cancellationDuringHideNeverDismissesOrActs() = runBlocking {
        val hidden = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            completeSheetAction({ hidden.await() }, { true }, { events += "dismiss" }, { events += "action" })
        }
        job.cancelAndJoin()
        hidden.complete(Unit)
        assertTrue(events.isEmpty())
    }

    @Test fun hideReturningInACancelledContextNeverActs() = runBlocking {
        var calls = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            completeSheetAction({ coroutineContext.cancel() }, { true }, { calls++ }, { calls++ })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, calls)
    }

    @Test fun refusedHideNeverDismissesOrActs() = runBlocking {
        var calls = 0
        completeSheetAction({}, { false }, { calls++ }, { calls++ })
        assertEquals(0, calls)
    }

    @Test fun hideFailurePropagatesWithoutAction() {
        var calls = 0
        val expected = IllegalStateException("hide failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking { completeSheetAction({ throw expected }, { true }, { calls++ }, { calls++ }) }
        }
        assertEquals(expected.message, actual.message)
        assertEquals(0, calls)
    }
}
