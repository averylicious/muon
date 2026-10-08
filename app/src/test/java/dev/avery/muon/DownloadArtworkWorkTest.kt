package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class DownloadArtworkWorkTest {
    private class Worker : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { tasks.removeFirst().run() }
    }

    @Test fun countBoundaryRetainsOneDrainAndRefusesExtraFetchUntilCompletion() {
        val worker = Worker(); val seen = ArrayList<String>()
        val work = DownloadArtworkWork(worker, { id, _ -> seen += id }, {}, maxJobs = 3)
        repeat(3) { assertTrue(work.fetch("id$it", "url")) }
        repeat(100) { assertFalse(work.fetch("overflow$it", "url")) }
        assertEquals(1, worker.tasks.size)
        worker.drain()
        assertEquals(listOf("id0", "id1", "id2"), seen)
        assertTrue(worker.tasks.isEmpty())
        assertTrue(work.fetch("retry", "url")); worker.drain()
        assertEquals("retry", seen.last())
    }

    @Test fun textBudgetIsExactAndReleasedWithoutRetainingRejectedMetadata() {
        val worker = Worker()
        val work = DownloadArtworkWork(worker, { _, _ -> }, {}, maxTextBytes = 74)
        assertFalse(work.fetch("id", "long")) // 64 + 2 * 6 = 76.
        assertTrue(worker.tasks.isEmpty())
        assertTrue(work.fetch("id", "url")) // 64 + 2 * 5 = 74.
        assertFalse(work.fetch("x", "y"))
        worker.drain()
        assertTrue(work.fetch("id", "url")); worker.drain()
    }

    @Test fun activeJobCountsAndDuplicateCannotChangeItsCoverIdentity() {
        val worker = Worker()
        lateinit var work: DownloadArtworkWork
        work = DownloadArtworkWork(worker, { id, url ->
            assertTrue(work.fetch(id, url))
            assertFalse(work.fetch(id, "different"))
            assertFalse(work.fetch("another", "url"))
        }, {}, maxJobs = 1)
        assertTrue(work.fetch("active", "url")); worker.drain()
        assertTrue(work.fetch("retry", "url")); worker.drain()
    }

    @Test fun removingWaitingFetchReplacesItWithOneCleanupAndCoalescesDuplicates() {
        val worker = Worker(); val events = ArrayList<String>()
        val work = DownloadArtworkWork(worker, { _, _ -> fail("Removed fetch must not run") },
            { events += "remove:$it" }, maxJobs = 1)
        assertTrue(work.fetch("id", "url"))
        work.remove("id"); work.remove("id")
        assertFalse(work.fetch("id", "url"))
        assertEquals(1, worker.tasks.size)
        worker.drain()
        assertEquals(listOf("remove:id"), events)
    }

    @Test fun callerCleanupAndLateActiveFetchCannotLeaveTheCoverResurrected() {
        val worker = Worker(); val files = HashSet<String>(); val events = ArrayList<String>()
        lateinit var work: DownloadArtworkWork
        work = DownloadArtworkWork(worker, { id, _ ->
            work.remove(id) // Active counts as the only slot, so cleanup runs on this caller.
            assertFalse(work.fetch(id, "url"))
            files += id // Model the blocking fetch publishing after the earlier delete.
            events += "published"
        }, { files -= it; events += "removed" }, maxJobs = 1)
        assertTrue(work.fetch("id", "url")); worker.drain()
        assertTrue(files.isEmpty())
        assertEquals(listOf("removed", "published", "removed"), events)
        assertTrue(worker.tasks.isEmpty())
    }

    @Test fun deletionDuringHeldPublicationRemovesTheRealEntryAndPreservesOtherCovers() {
        val dir = java.nio.file.Files.createTempDirectory("held-saved-cover").toFile()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val publish = java.util.concurrent.CountDownLatch(1)
        val art = DownloadArt(dir)
        fun file(id: String) = java.io.File(dir, "entry-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("muon-saved-entry\u0000$id".toByteArray()).joinToString("") { "%02x".format(it) })
        file("other").writeBytes(byteArrayOf(7, 8))
        val legacy = java.io.File(dir, "older-per-track-cover").apply { writeBytes(byteArrayOf(9)) }
        val work = DownloadArtworkWork(worker, { id, _ ->
            entered.countDown()
            check(publish.await(5, java.util.concurrent.TimeUnit.SECONDS))
            file(id).writeBytes(byteArrayOf(1, 2, 3))
        }, art::removeEntry, maxJobs = 1)
        try {
            assertTrue(work.fetch("active", "url"))
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            work.remove("active")
            assertFalse(work.fetch("active", "url"))
            publish.countDown()
            worker.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse(art.hasEntry("active"))
            assertArrayEquals(byteArrayOf(7, 8), art.forEntry("other"))
            assertArrayEquals(byteArrayOf(9), legacy.readBytes())
        } finally {
            publish.countDown(); worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
            dir.deleteRecursively()
        }
    }

    @Test fun cleanupDisplacesOptionalFetchButNeverAnotherCleanup() {
        val worker = Worker(); val events = ArrayList<String>()
        val work = DownloadArtworkWork(worker, { id, _ -> events += "fetch:$id" },
            { events += "remove:$it" }, maxJobs = 2)
        assertTrue(work.fetch("old", "url")); assertTrue(work.fetch("kept", "url"))
        work.remove("gone") // Displaces the oldest optional waiting fetch.
        worker.drain()
        assertEquals(listOf("fetch:kept", "remove:gone"), events)
        events.clear()
        work.remove("a"); work.remove("b"); work.remove("c")
        assertEquals(listOf("remove:c"), events) // Caller fallback, no unlimited cleanup queue.
        assertEquals(1, worker.tasks.size); worker.drain()
        assertEquals(listOf("remove:c", "remove:a", "remove:b"), events)
    }

    @Test fun executorRejectionReleasesAdmissionAndCleanupStillRuns() {
        val worker = Worker(); var rejecting = true; val removed = ArrayList<String>()
        val executor = Executor { if (rejecting) throw RejectedExecutionException() else worker.execute(it) }
        val work = DownloadArtworkWork(executor, { _, _ -> }, { removed += it }, maxJobs = 1)
        assertFalse(work.fetch("first", "url"))
        work.remove("id")
        assertEquals(listOf("id"), removed)
        rejecting = false
        assertTrue(work.fetch("retry", "url")); worker.drain()
    }

    @Test fun fetchFailureDoesNotStrandQueuedCleanupOrFutureJobs() {
        val worker = Worker(); val removed = ArrayList<String>()
        val work = DownloadArtworkWork(worker, { _, _ -> throw java.io.IOException("cover failed") },
            { removed += it })
        assertTrue(work.fetch("bad", "url")); work.remove("other"); worker.drain()
        assertEquals(listOf("other"), removed)
        assertTrue(work.fetch("retry", "url")); worker.drain()
        assertTrue(worker.tasks.isEmpty())
    }
}
