package dev.avery.muon

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PageBackOwnershipTest {
    @Test fun anEmptyProgressSecondBackCancelsTheOldPageRestore() = runTest {
        val peek = BackPeek()
        val finishSpring = CompletableDeferred<Unit>()
        var page = "album"
        val first = peek.begin()
        peek.restore(this, first) { finishSpring.await(); page = "album" }
        runCurrent() // The first cancelled gesture is waiting for its spring to finish.
        val second = peek.begin()
        page = "library" // Normal/hardware Back commits without a progress snapTo.
        finishSpring.complete(Unit)
        runCurrent()
        assertEquals("library", page)
        assertFalse(peek.owns(first))
        assertTrue(peek.owns(second))
    }

    @Test fun anObsoleteCancellationCannotScheduleCleanupForANewerGesture() = runTest {
        val peek = BackPeek()
        val first = peek.begin()
        val second = peek.begin()
        var showingPlayerUnderOverlay = true
        // The old handler's catch may be delivered after the new handler starts.
        peek.restore(this, first) { showingPlayerUnderOverlay = false }
        runCurrent()
        assertTrue(showingPlayerUnderOverlay)
        assertTrue(peek.owns(second))
    }

    @Test fun logicalNavigationCancelsEvenAQueuedRestoreAndOldCommit() = runTest {
        val peek = BackPeek()
        val gesture = peek.begin()
        var destination = "artist"
        peek.restore(this, gesture) { destination = "album" }
        peek.interrupt() // The logical page effect changes the transition destination.
        runCurrent()
        assertEquals("artist", destination)
        assertFalse(peek.owns(gesture))
    }
}
