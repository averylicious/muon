package dev.avery.muon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLoadsTest {
    @Test fun oldCleanupCannotReleaseReplacementOrAllowAThirdLoad() = runTest {
        var busy = false
        val loads = LibraryLoads(this) { busy = it }
        val oldCleanup = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        loads.start {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { oldCleanup.await() } }
        }
        loads.cancel()
        assertFalse(busy)
        loads.start { replacement.await() }
        assertTrue(busy)
        runCurrent() // Old cancellation is now waiting in delayed cleanup.
        oldCleanup.complete(Unit)
        runCurrent()
        assertTrue("Old finally must not clear the replacement's busy state", busy)
        var thirdStarted = false
        loads.start { thirdStarted = true }
        assertFalse(thirdStarted)
        replacement.complete(Unit)
        runCurrent()
        assertFalse(busy)
    }

    @Test fun cancelledResultCannotPublishOverAReplacement() = runTest {
        var displayed = "empty"
        var busy = false
        val loads = LibraryLoads(this) { busy = it }
        val oldResult = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        loads.start {
            // Models a provider that completes despite cancellation (e.g. blocking filesystem IO).
            withContext(NonCancellable) { oldResult.await() }
            ensureCurrent()
            displayed = "obsolete"
        }
        loads.cancel()
        loads.start { displayed = "replacement"; replacement.await() }
        oldResult.complete(Unit)
        runCurrent()
        assertEquals("replacement", displayed)
        assertTrue(busy)
        replacement.complete(Unit)
        runCurrent()
        assertFalse(busy)
    }

    @Test fun obsoleteErrorCannotReplaceCurrentError() = runTest {
        var error: String? = null
        val loads = LibraryLoads(this) { }
        val oldFailure = CompletableDeferred<Unit>()
        loads.start {
            try {
                withContext(NonCancellable) { oldFailure.await(); throw IllegalStateException("old") }
            } catch (failure: IllegalStateException) {
                ensureCurrent()
                error = failure.message
            }
        }
        loads.cancel()
        loads.start { error = "current" }
        oldFailure.complete(Unit)
        runCurrent()
        assertEquals("current", error)
    }

    @Test fun synchronousCompletionAndHandledFailurePermitRetry() = runTest {
        val states = mutableListOf<Boolean>()
        val loads = LibraryLoads(this) { states += it }
        var executions = 0
        loads.start { executions++ }
        loads.start {
            try { throw IllegalArgumentException("bad address") }
            catch (_: IllegalArgumentException) { ensureCurrent(); executions++ }
        }
        loads.start { executions++ }
        assertEquals(3, executions)
        assertEquals(listOf(true, false, true, false, true, false), states)
    }

    @Test fun cancelledScopeNeverPublishesAndDoesNotLeaveBusySet() = runTest {
        val parent = Job().apply { cancel() }
        var busy = false
        var published = false
        val loads = LibraryLoads(CoroutineScope(coroutineContext + parent)) { busy = it }
        loads.start { published = true }
        assertFalse(published)
        assertFalse(busy)
    }
}
