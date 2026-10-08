package dev.avery.muon

import android.net.Uri
import android.os.Looper
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteBlobTooBigException
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual Media3 initialization/tasks + native SQLite and disposable cache. No Android service or phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RetainedDownloadStartupTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private var manager: DownloadManager? = null
    private val payload = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database, "retained_startup")
    }
    @After fun tearDown() {
        try { manager?.release(); cache.release() } finally { database.close() }
    }

    @Test fun aThrowingRemovalStillLosesTheIndexRowWithoutAStartupGuard() {
        val pending = put("pending", "pending", Download.STATE_REMOVING)
        seed("pending")
        val removals = AtomicInteger()
        val factory = DownloaderFactory { object : Downloader {
            override fun download(listener: Downloader.ProgressListener?) = error("No downloading")
            override fun cancel() = Unit
            override fun remove() { removals.incrementAndGet(); throw IOException("Refused unsafe removal") }
        } }
        start(index, factory)
        pumpUntil { index.getDownload(pending.request.id) == null }
        assertEquals(1, removals.get())
        assertTrue("Throwing protects bytes but loses their record", cache.isCached("pending", 0, 4))
    }

    @Test fun unguardedPendingRemovalActuallyDeletesAnotherRowsSharedCachedAudio() {
        put("pending", "shared", Download.STATE_REMOVING)
        put("other", "shared", Download.STATE_COMPLETED)
        seed("shared")
        val real = DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache), Runnable::run)
        start(index, real)
        pumpUntil { index.getDownload("pending") == null }
        assertNotNull(index.getDownload("other"))
        assertTrue("The other row survives while its actual bytes disappear", cache.getCachedSpans("shared").isEmpty())
    }

    @Test fun failedInitialCensusNeverRescansAndPausesANewerProcessCommand() {
        put("old", "old", Download.STATE_REMOVING)
        seed("old")
        val scans = AtomicInteger()
        val onceUnreadable = object : WritableDownloadIndex by index {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                if (scans.incrementAndGet() == 1) throw IOException("Initial census failed")
                return index.getDownloads(*states)
            }
        }
        val guard = RetainedDownloadIndex(onceUnreadable)
        start(guard, DownloaderFactory { error("No tasks may start") })
        // Exercise the manager directly as a positive control after its failed initialization.
        val fresh = DownloadRequest.Builder("saved/new", Uri.parse("http://127.0.0.1:7814/9"))
            .setCustomCacheKey("saved/new").build()
        requireNotNull(manager).addDownload(fresh, 7)
        pumpUntil { index.getDownload(fresh.id)?.stopReason == 7 }
        assertThrows(IOException::class.java) { guard.getDownloads().close() }
        assertEquals(1, scans.get())
        assertEquals(7, index.getDownload(fresh.id)?.stopReason)
        assertEquals(fresh, index.getDownload(fresh.id)?.request)
        assertEquals(Download.STATE_REMOVING, index.getDownload("old")?.state)
        assertTrue(cache.isCached("old", 0, 4))
    }

    @Test fun anEarlyMainThreadCountNeverWritesStartupStatesAndTheWorkerCanStillInitialize() {
        put("pending", "pending", Download.STATE_REMOVING)
        seed("pending")
        val guard = RetainedDownloadIndex(index)
        assertThrows(IOException::class.java) { guard.getDownloads().close() }
        assertEquals(Download.STATE_REMOVING, index.getDownload("pending")?.state)
        start(guard, DownloaderFactory { error("The worker must stop the pending removal") })
        assertEquals(RETAINED_STOP_REASON, index.getDownload("pending")?.stopReason)
        assertTrue(cache.isCached("pending", 0, 4))
    }

    @Test fun unreadableStartupCensusStartsNoTaskAndKeepsRawRowsAndBytes() {
        val pending = put("pending", "pending", Download.STATE_REMOVING)
        seed("pending")
        val unreadable = object : WritableDownloadIndex by index {
            override fun getDownloads(vararg states: Int): DownloadCursor = throw IOException("Fixture census unavailable")
        }
        val creations = AtomicInteger()
        start(RetainedDownloadIndex(unreadable), DownloaderFactory {
            creations.incrementAndGet(); error("An unreadable startup must start no task")
        })
        assertEquals(0, creations.get())
        assertEquals(pending.request, index.getDownload("pending")?.request)
        assertEquals(Download.STATE_REMOVING, index.getDownload("pending")?.state)
        assertTrue(cache.isCached("pending", 0, 4))
    }

    @Test fun pendingAliasedRemovalIsStoppedBeforeTheActualProgressiveDownloaderDeletesBytes() {
        val pending = put("pending", "shared", Download.STATE_REMOVING)
        val other = put("other", "shared", Download.STATE_COMPLETED)
        seed("shared")
        val real = DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache), Runnable::run)
        val creations = AtomicInteger()
        val factory = DownloaderFactory { request -> creations.incrementAndGet(); real.createDownloader(request) }
        start(RetainedDownloadIndex(index), factory)
        val held = requireNotNull(index.getDownload("pending"))
        assertEquals(Download.STATE_STOPPED, held.state)
        assertEquals(RETAINED_STOP_REASON, held.stopReason)
        assertEquals(pending.request, held.request)
        assertEquals(other.request, requireNotNull(index.getDownload("other")).request)
        assertEquals(0, creations.get())
        assertTrue(cache.isCached("shared", 0, 4))
        val entries = savedInventory(SavedShelf.Phone, rows(), cache, PlayedClaims.none()) { false }
        val saved = entries.single { it.ref.requestId == "pending" }
        assertTrue(saved.stoppedAfterRestart)
        assertTrue("Complete local bytes remain separately playable", saved.complete)
        assertFalse("Shared bytes still cannot be deleted", saved.removable)
        assertEquals(saved.ref.handle, saved.mediaItem().mediaId)
    }

    @Test fun queuedDownloadingAndRestartingRequestsKeepAllDataAndRemainStoppedOnAnotherRestart() {
        val originals = listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING)
            .mapIndexed { i, state -> put("entry-$i", "entry-$i", state) }
        originals.forEach { seed(it.request.id, partial = true) }
        val creations = AtomicInteger()
        val never = DownloaderFactory { creations.incrementAndGet(); error("Retained tasks must not start") }
        start(RetainedDownloadIndex(index), never)
        requireNotNull(manager).resumeDownloads()
        pumpUntil { requireNotNull(manager).isIdle }
        for (old in originals) {
            val held = requireNotNull(index.getDownload(old.request.id))
            assertEquals(old.request, held.request)
            assertEquals(old.startTimeMs, held.startTimeMs)
            assertEquals(old.updateTimeMs, held.updateTimeMs)
            assertEquals(old.contentLength, held.contentLength)
            assertEquals(old.bytesDownloaded, held.bytesDownloaded)
            assertEquals(old.percentDownloaded, held.percentDownloaded, 0f)
            assertEquals(Download.STATE_STOPPED, held.state)
            assertEquals(RETAINED_STOP_REASON, held.stopReason)
        }
        requireNotNull(manager).release(); manager = null
        start(RetainedDownloadIndex(index), never)
        assertEquals(0, creations.get())
        val entries = savedInventory(SavedShelf.Phone, rows(), cache, PlayedClaims.none()) { false }
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.stoppedAfterRestart && !it.complete })
        assertTrue(originals.all { cache.isCached(it.request.id, 0, 2) })
    }

    @Test fun finishedRowsAreUntouchedAndFreshCurrentProcessCommandsStillWork() {
        val completed = put("finished", "finished", Download.STATE_COMPLETED)
        seed("finished")
        val real = DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache), Runnable::run)
        start(RetainedDownloadIndex(index), real)
        assertEquals(completed.request, requireNotNull(index.getDownload("finished")).request)
        assertEquals(Download.STATE_COMPLETED, index.getDownload("finished")?.state)
        // A new name remains addable in this process; explicitly stopped to forbid network in this fixture.
        val fresh = DownloadRequest.Builder("saved/new", Uri.parse("http://127.0.0.1:7814/api1/file/9"))
            .setCustomCacheKey("saved/new").setData(byteArrayOf(8)).build()
        requireNotNull(manager).addDownload(fresh, 7)
        pumpUntil { index.getDownload(fresh.id)?.stopReason == 7 }
        assertEquals(fresh, index.getDownload(fresh.id)?.request)
        // A later intentional, sole-owner removal is not intercepted by the startup decorator.
        requireNotNull(manager).removeDownload("finished")
        pumpUntil { index.getDownload("finished") == null }
        assertTrue(cache.getCachedSpans("finished").isEmpty())
        assertNotNull(index.getDownload(fresh.id))
    }

    // Use a deliberately small native window; this is a real SQLite fill failure, not a mocked throw.
    // The pinned Media3 cursor exposes no window option, so test-only reflection reaches its Cursor.
    private class SmallWindowIndex(private val actual: DefaultDownloadIndex) : WritableDownloadIndex by actual {
        val cursors = java.util.concurrent.CopyOnWriteArrayList<DownloadCursor>()
        override fun getDownloads(vararg states: Int): DownloadCursor {
            val cursor = actual.getDownloads(*states)
            val field = cursor.javaClass.getDeclaredField("cursor").apply { isAccessible = true }
            val sqlite = field.get(cursor) as SQLiteCursor
            sqlite.setWindow(CursorWindow("retained-small-window", 32L * 1024))
            cursors += cursor
            return cursor
        }
    }

    @Test fun nativeWindowCannotReadALargeRowAndTheStartupGuardRefusesWithoutChangingIt() {
        val original = put("oversized", "shared", Download.STATE_REMOVING, ByteArray(256 * 1024) { 9 })
        seed("shared")
        val small = SmallWindowIndex(index)
        small.getDownloads().use { cursor ->
            assertThrows(SQLiteBlobTooBigException::class.java) { cursor.moveToNext() }
        }
        val guard = RetainedDownloadIndex(small)
        val creations = AtomicInteger()
        start(guard, DownloaderFactory { creations.incrementAndGet(); error("No task may start") })
        assertTrue(requireNotNull(manager).currentDownloads.isEmpty())
        assertEquals(0, creations.get())
        val after = requireNotNull(index.getDownload("oversized")) // Normal window: preserve original row.
        assertEquals(original.request, after.request)
        assertEquals(original.state, after.state)
        assertEquals(original.updateTimeMs, after.updateTimeMs)
        assertTrue(cache.isCached("shared", 0, 4))
        val count = small.cursors.size
        val refused = assertThrows(IOException::class.java) { guard.getDownloads().close() }
        assertTrue(refused.cause is SQLiteBlobTooBigException)
        assertEquals("Latched refusal never rescans", count, small.cursors.size)
        assertTrue(small.cursors.all { it.isClosed })
    }

    @Test fun nativeWindowFailureInTheManagersStoppedRowScanBecomesAnIoRefusalToo() {
        val original = put("stopped", "stopped", Download.STATE_STOPPED, ByteArray(256 * 1024) { 8 })
        seed("stopped", partial = true)
        val small = SmallWindowIndex(index)
        val guard = RetainedDownloadIndex(small)
        val creations = AtomicInteger()
        start(guard, DownloaderFactory { creations.incrementAndGet(); error("No task may start") })
        assertTrue(requireNotNull(manager).currentDownloads.isEmpty())
        assertEquals(0, creations.get())
        assertEquals(original.request, index.getDownload("stopped")?.request)
        assertEquals(original.state, index.getDownload("stopped")?.state)
        assertTrue(cache.isCached("stopped", 0, 2))
        assertTrue(small.cursors.all { it.isClosed })
        // Preservation completed; subsequent lazy reads fail as IO rather than a worker-killing runtime error.
        guard.getDownloads().use { cursor ->
            val refused = assertThrows(IOException::class.java) { cursor.moveToNext() }
            assertTrue(refused.cause is SQLiteBlobTooBigException)
        }
    }

    // ---- #253: the startup scan keeps only IDs; each row is read again alone just before its write ----

    private val largeTags = ByteArray(256 * 1024) { (it % 251).toByte() }

    @Test fun manyLargeRetainedRowsAreEachReadAgainAloneAfterTheScanClosesAndKeepEveryRecord() {
        val song = TauonTrack(42, "Kept title", "Artist", "Album", 1000, true, false)
        val states = listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_REMOVING,
            Download.STATE_RESTARTING, Download.STATE_QUEUED)
        val originals = states.mapIndexed { i, state ->
            // One valid displayable record; the others large raw tags, each different.
            put("entry-$i", "entry-$i", state, if (i == 0) encodeSong(song) else largeTags + byteArrayOf(i.toByte()),
                start = 100L + i, update = 200L + i)
        }
        originals.forEach { seed(it.request.id, partial = true) }
        val finished = put("finished", "finished", Download.STATE_COMPLETED, largeTags, start = 7, update = 8)
        seed("finished")
        val observed = Observed(index)
        val creations = AtomicInteger()
        val never = DownloaderFactory { creations.incrementAndGet(); error("Retained tasks must not start") }
        start(RetainedDownloadIndex(observed), never)
        requireNotNull(manager).resumeDownloads()
        pumpUntil { requireNotNull(manager).isIdle }
        assertEquals(0, creations.get())
        assertTrue(requireNotNull(manager).currentDownloads.all { it.state == Download.STATE_STOPPED })
        // Before the manager's own startup step: one closed scan, then each ID read and written in turn.
        val guarded = observed.events.takeWhile { it != "queued" }
        assertEquals("scan", guarded.first())
        val pairs = guarded.drop(1).chunked(2)
        assertTrue(guarded.toString(), pairs.all { it.size == 2 && it[0] == "read:" + it[1].removePrefix("put:") })
        assertEquals(originals.map { it.request.id }.toSet(), pairs.map { it[0].removePrefix("read:") }.toSet())
        assertEquals(originals.size, pairs.size)
        assertEquals("No read or write while a scan was open", 0, observed.duringScan)
        assertTrue(observed.retainedCursors.single().isClosed)
        for (old in originals) {
            val held = requireNotNull(index.getDownload(old.request.id))
            assertEquals(old.request, held.request) // Address, key and the stored tags, byte for byte.
            assertEquals(old.startTimeMs, held.startTimeMs)
            assertEquals(old.updateTimeMs, held.updateTimeMs)
            assertEquals(old.contentLength, held.contentLength)
            assertEquals(old.bytesDownloaded, held.bytesDownloaded)
            assertEquals(old.percentDownloaded, held.percentDownloaded, 0f)
            assertEquals(Download.STATE_STOPPED, held.state)
            assertEquals(RETAINED_STOP_REASON, held.stopReason)
            assertTrue(cache.isCached(old.request.id, 0, 2))
        }
        val done = requireNotNull(index.getDownload("finished"))
        assertEquals(finished.request, done.request)
        assertEquals(Download.STATE_COMPLETED, done.state)
        assertEquals(finished.updateTimeMs, done.updateTimeMs)
        assertTrue(cache.isCached("finished", 0, 4))
        val shown = savedInventory(SavedShelf.Phone, rows(), cache, PlayedClaims.none()) { false }
            .single { it.ref.requestId == "entry-0" }
        assertEquals(song, shown.displaySong)
        assertTrue(shown.stoppedAfterRestart)
    }

    @Test fun aRowThatCannotBeReadAgainLatchesTheFailureStartsNoTaskAndNeverRescans() {
        val first = put("a", "shared", Download.STATE_REMOVING, largeTags)
        val second = put("b", "b", Download.STATE_REMOVING, largeTags)
        val other = put("other", "shared", Download.STATE_COMPLETED)
        seed("shared"); seed("b")
        val observed = Observed(index) { id -> if (id == "b") throw IOException("Injected row read failure") }
        val guard = RetainedDownloadIndex(observed)
        val creations = AtomicInteger()
        start(guard, DownloaderFactory { creations.incrementAndGet(); error("A failed startup must start no task") })
        assertEquals(0, creations.get())
        assertTrue(requireNotNull(manager).currentDownloads.isEmpty())
        assertEquals(Download.STATE_REMOVING, index.getDownload("b")?.state)
        assertEquals(second.request, index.getDownload("b")?.request)
        // "a" was stopped or not yet reached, depending on the scan's order; never removed or rewritten.
        val a = requireNotNull(index.getDownload("a"))
        assertEquals(first.request, a.request)
        assertTrue(a.state == Download.STATE_REMOVING || (a.state == Download.STATE_STOPPED && a.stopReason == RETAINED_STOP_REASON))
        assertEquals(other.request, index.getDownload("other")?.request)
        assertTrue(cache.isCached("shared", 0, 4))
        assertTrue(cache.isCached("b", 0, 4))
        assertTrue(observed.retainedCursors.single().isClosed)
        // A newer command in this process works, and the latched guard never rescans or stops it.
        val fresh = DownloadRequest.Builder("saved/new", Uri.parse("http://127.0.0.1:7814/9"))
            .setCustomCacheKey("saved/new").build()
        requireNotNull(manager).addDownload(fresh, 7)
        pumpUntil { index.getDownload(fresh.id)?.stopReason == 7 }
        val thrown = assertThrows(IOException::class.java) { guard.getDownloads().close() }
        assertEquals("Injected row read failure", thrown.message)
        assertEquals(1, observed.retainedCursors.size)
        assertEquals(7, index.getDownload(fresh.id)?.stopReason)
        assertEquals(0, creations.get())
    }

    @Test fun aRowGoneBeforeItsWriteIsNotRecreatedAndNothingStarts() {
        val kept = changedDuringStartup { id -> if (id == "changing") index.removeDownload(id) }
        assertNull("A missing row is never recreated", index.getDownload("changing"))
        assertEquals(kept.request, index.getDownload("kept")?.request)
    }

    @Test fun aRowFinishedBeforeItsWriteIsNotOverwrittenAndNothingStarts() {
        val kept = changedDuringStartup { id ->
            if (id == "changing") index.putDownload(Download(requireNotNull(index.getDownload(id)).request,
                Download.STATE_COMPLETED, 123, 999, 4, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        }
        val newer = requireNotNull(index.getDownload("changing"))
        assertEquals(Download.STATE_COMPLETED, newer.state)
        assertEquals(999L, newer.updateTimeMs)
        assertEquals(kept.request, index.getDownload("kept")?.request)
    }

    /**
     * Starts a guarded manager over two unfinished rows whose "changing" row is changed by [change] the
     * moment it is read again: the guard refuses (latched), no task starts, and bytes stay.
     */
    private fun changedDuringStartup(change: (String) -> Unit): Download {
        put("changing", "changing", Download.STATE_REMOVING, largeTags)
        val kept = put("kept", "kept", Download.STATE_QUEUED, largeTags)
        seed("changing"); seed("kept", partial = true)
        val observed = Observed(index, change)
        val guard = RetainedDownloadIndex(observed)
        val creations = AtomicInteger()
        start(guard, DownloaderFactory { creations.incrementAndGet(); error("A refused startup must start no task") })
        requireNotNull(manager).resumeDownloads()
        pumpUntil { requireNotNull(manager).isIdle }
        assertEquals(0, creations.get())
        assertTrue(requireNotNull(manager).currentDownloads.isEmpty())
        assertThrows(IOException::class.java) { guard.getDownloads().close() }
        assertEquals(1, observed.retainedCursors.size)
        assertTrue(observed.retainedCursors.single().isClosed)
        assertTrue(cache.isCached("changing", 0, 4))
        assertTrue(cache.isCached("kept", 0, 2))
        val held = requireNotNull(index.getDownload("kept"))
        assertTrue(held.state == Download.STATE_QUEUED || (held.state == Download.STATE_STOPPED && held.stopReason == RETAINED_STOP_REASON))
        return kept
    }

    /**
     * Delegates to the real index and records the guard's work: its state-filtered scan (the only one before
     * the manager's own startup step, which the guard runs after inspecting), each single-row read and write,
     * and anything done while that scan is open.
     */
    private class Observed(private val actual: DefaultDownloadIndex, private val onRead: (String) -> Unit = {}) :
        WritableDownloadIndex by actual {
        val events: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val retainedCursors = java.util.concurrent.CopyOnWriteArrayList<DownloadCursor>()
        @Volatile private var scanning = 0
        @Volatile private var queued = false
        @Volatile var duringScan = 0
        override fun getDownloads(vararg states: Int): DownloadCursor {
            val cursor = actual.getDownloads(*states)
            if (states.isEmpty() || queued) return cursor
            events += "scan"
            retainedCursors += cursor
            scanning++
            return object : DownloadCursor by cursor {
                override fun close() { if (!cursor.isClosed) scanning--; cursor.close() }
            }
        }
        override fun getDownload(id: String): Download? {
            if (scanning > 0) duringScan++
            events += "read:$id"
            onRead(id)
            return actual.getDownload(id)
        }
        override fun putDownload(download: Download) {
            if (scanning > 0) duringScan++
            events += "put:${download.request.id}"
            actual.putDownload(download)
        }
        override fun setDownloadingStatesToQueued() { queued = true; events += "queued"; actual.setDownloadingStatesToQueued() }
    }

    // ---- #253: the manager's startup view (ManagerStartupIndex over RetainedDownloadIndex, as in production) ----

    private val startupStates = intArrayOf(Download.STATE_QUEUED, Download.STATE_STOPPED, Download.STATE_DOWNLOADING,
        Download.STATE_REMOVING, Download.STATE_RESTARTING)

    private fun production(over: WritableDownloadIndex = index) = ManagerStartupIndex(RetainedDownloadIndex(over))

    /** A row stopped at an earlier restart, as RetainedDownloadIndex leaves it. */
    private fun retained(id: String, data: ByteArray, start: Long = 123, update: Long = 456): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/$id"))
            .setCustomCacheKey(id).setData(data).build()
        val progress = DownloadProgress().apply { bytesDownloaded = 2; percentDownloaded = 50f }
        return Download(request, Download.STATE_STOPPED, start, update, 4, RETAINED_STOP_REASON,
            Download.FAILURE_REASON_NONE, progress).also(index::putDownload)
    }

    @Test fun aRestartWithManyRetainedRowsLoadsNoneIntoTheManagerAndKeepsEveryRecordAndByte() {
        val tags = ByteArray(64 * 1024) { (it % 249).toByte() }
        val unfinished = listOf(Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_REMOVING,
            Download.STATE_RESTARTING)
        val originals = (0 until 64).map { i ->
            val data = tags + byteArrayOf(i.toByte())
            if (i % 2 == 0) retained("old-$i", data, start = 1000L + i, update = 2000L + i)
            else put("new-$i", "new-$i", unfinished[(i / 2) % unfinished.size], data, start = 1000L + i, update = 2000L + i)
        }
        originals.forEach { seed(it.request.id, partial = true) }
        val finished = put("finished", "finished", Download.STATE_COMPLETED, tags)
        seed("finished")
        val creations = AtomicInteger()
        val never = DownloaderFactory { creations.incrementAndGet(); error("Retained tasks must not start") }
        repeat(2) { restart ->
            start(production(), never)
            requireNotNull(manager).resumeDownloads()
            pumpUntil { requireNotNull(manager).isIdle }
            assertTrue("Restart $restart: nothing retained is held by the manager", requireNotNull(manager).currentDownloads.isEmpty())
            assertEquals(0, creations.get())
            for (old in originals) {
                val held = requireNotNull(index.getDownload(old.request.id))
                assertEquals(old.request, held.request)
                assertEquals(old.startTimeMs, held.startTimeMs)
                assertEquals(old.updateTimeMs, held.updateTimeMs)
                assertEquals(old.contentLength, held.contentLength)
                assertEquals(old.bytesDownloaded, held.bytesDownloaded)
                assertEquals(old.percentDownloaded, held.percentDownloaded, 0f)
                assertEquals(Download.STATE_STOPPED, held.state)
                assertEquals(RETAINED_STOP_REASON, held.stopReason)
                assertTrue(cache.isCached(old.request.id, 0, 2))
            }
            assertEquals(finished.request, index.getDownload("finished")?.request)
            assertEquals(Download.STATE_COMPLETED, index.getDownload("finished")?.state)
            // Muon's own reads through the manager's index stay complete: all states, and STOPPED alone.
            val view = requireNotNull(manager).downloadIndex
            assertEquals(65, view.getDownloads().use { it.count })
            assertEquals(64, view.getDownloads(Download.STATE_STOPPED).use { it.count })
            assertEquals(1, view.getDownloads(Download.STATE_COMPLETED).use { it.count })
            requireNotNull(manager).release(); manager = null
        }
    }

    @Test fun onlyTheExactStartupQueryLeavesStoppedRowsOutAnyOtherShapeStaysComplete() {
        retained("old", byteArrayOf(1))
        put("queued", "queued", Download.STATE_QUEUED)
        put("finished", "finished", Download.STATE_COMPLETED)
        val view = ManagerStartupIndex(index) // The shape rule alone; the manager tests use the full composition.
        fun ids(vararg states: Int) = view.getDownloads(*states).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.download.request.id) }
        }
        assertEquals(setOf("queued"), ids(*startupStates))
        val reordered = startupStates.reversedArray()
        assertEquals("Another order is not the startup query", setOf("old", "queued"), ids(*reordered))
        assertEquals(setOf("old", "queued"), ids(*(startupStates + Download.STATE_STOPPED)))
        assertEquals(setOf("old", "queued", "finished"), ids(*(startupStates + Download.STATE_COMPLETED)))
        assertEquals(setOf("old", "queued"), ids(*startupStates.copyOf(4)))
        assertEquals(setOf("old"), ids(Download.STATE_STOPPED))
        assertEquals(setOf("old", "queued", "finished"), ids())
        assertEquals(RETAINED_STOP_REASON, view.getDownload("old")?.stopReason)
    }

    /** A manager's listener record: each change's state, and each removal. */
    private class Events : DownloadManager.Listener {
        val seen: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun onDownloadChanged(m: DownloadManager, download: Download, finalException: Exception?) {
            seen += "changed:${download.request.id}:${download.state}"
        }
        override fun onDownloadRemoved(m: DownloadManager, download: Download) { seen += "removed:${download.request.id}" }
    }

    /** Runs [act] on a manager over a fresh copy of one retained row, with or without the startup view. */
    private fun withRetainedRow(name: String, view: Boolean, data: ByteArray,
        act: (DownloadManager, DefaultDownloadIndex, SimpleCache) -> Unit): List<String> {
        val own = DefaultDownloadIndex(database, name)
        val ownCache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database).also { it.checkInitialization() }
        val request = DownloadRequest.Builder("kept", Uri.parse("http://127.0.0.1:7814/api1/file/kept"))
            .setCustomCacheKey("kept").setData(data).build()
        own.putDownload(Download(request, Download.STATE_STOPPED, 123, 456, 4, RETAINED_STOP_REASON,
            Download.FAILURE_REASON_NONE, DownloadProgress().apply { bytesDownloaded = 4; percentDownloaded = 100f }))
        val hole = requireNotNull(ownCache.startReadWrite("kept", 0, 4))
        try {
            val file = ownCache.startFile("kept", 0, 4)
            file.writeBytes(payload); ownCache.commitFile(file, 4)
        } finally { ownCache.releaseHoleSpan(hole) }
        val real = DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(ownCache), Runnable::run)
        val guard = RetainedDownloadIndex(own)
        val chosen = DownloadManager(RuntimeEnvironment.getApplication(), if (view) ManagerStartupIndex(guard) else guard, real)
        val events = Events().also(chosen::addListener)
        try {
            pumpUntil { chosen.isInitialized && chosen.isIdle }
            assertEquals(if (view) emptyList() else listOf("kept"), chosen.currentDownloads.map { it.request.id })
            act(chosen, own, ownCache)
            pumpUntil { chosen.isIdle }
            return events.seen.toList()
        } finally { chosen.release(); ownCache.release() }
    }

    @Test fun anOmittedRowIsStillRemovedByItsOwnCommandWithTheSameEvents() {
        val outcomes = listOf(false, true).map { view ->
            var removed = false
            val events = withRetainedRow("remove_${if (view) "view" else "base"}", view, byteArrayOf(5, 6)) { manager, own, ownCache ->
                manager.removeDownload("kept")
                pumpUntil { own.getDownload("kept") == null }
                removed = ownCache.getCachedSpans("kept").isEmpty()
            }
            assertTrue("view=$view: the row's own bytes went with it", removed)
            events
        }
        assertEquals(listOf("changed:kept:${Download.STATE_REMOVING}", "removed:kept"), outcomes[0])
        assertEquals("The same Media3 events with and without the view", outcomes[0], outcomes[1])
    }

    @Test fun aFreshAddForAnOmittedRowMergesItsFullStoredRecordAsBefore() {
        val stored = ByteArray(32 * 1024) { (it % 241).toByte() }
        val results = listOf(false, true).map { view ->
            var after: Download? = null
            withRetainedRow("add_${if (view) "view" else "base"}", view, stored) { manager, own, _ ->
                val again = requireNotNull(own.getDownload("kept")).request
                manager.addDownload(again, 7) // Stopped by reason: merged and kept, no network in this fixture.
                pumpUntil { own.getDownload("kept")?.stopReason == 7 }
                after = own.getDownload("kept")
            }
            requireNotNull(after)
        }
        for (merged in results) {
            assertArrayEquals(stored, merged.request.data)
            assertEquals(Download.STATE_STOPPED, merged.state)
            assertEquals(7, merged.stopReason)
        }
        assertEquals(results[0].request, results[1].request)
        assertEquals(results[0].startTimeMs, results[1].startTimeMs)
        assertEquals(results[0].contentLength, results[1].contentLength)
    }

    /**
     * Pinned Media3 1.11.0 hazards of the two bulk commands for a row the manager never loaded. Called here
     * directly on the manager only: production refuses both before Media3 (OfflineStore.admitCommand).
     */
    @Test fun directBulkCommandsWouldTreatAnOmittedRowDifferentlyWhichIsWhyTheyAreRefused() {
        val old = retained("old", byteArrayOf(4, 4))
        seed("old")
        val creations = AtomicInteger()
        start(production(), DownloaderFactory { creations.incrementAndGet(); error("No task may start") })
        val manager = requireNotNull(manager)
        // setStopReason(id) does not read an omitted row; its index fallback updates only completed/failed rows.
        manager.setStopReason("old", Download.STOP_REASON_NONE)
        pumpUntil { manager.isIdle }
        assertEquals(RETAINED_STOP_REASON, index.getDownload("old")?.stopReason)
        assertEquals(Download.STATE_STOPPED, index.getDownload("old")?.state)
        // removeAllDownloads marks every index row REMOVING but starts no task for one it never loaded.
        manager.removeAllDownloads()
        pumpUntil { manager.isIdle }
        assertEquals(Download.STATE_REMOVING, index.getDownload("old")?.state)
        assertEquals(old.request, index.getDownload("old")?.request)
        assertEquals(0, creations.get())
        assertTrue("Its bytes stay", cache.isCached("old", 0, 4))
    }

    private fun start(downloadIndex: WritableDownloadIndex, factory: DownloaderFactory) {
        manager = DownloadManager(RuntimeEnvironment.getApplication(), downloadIndex, factory)
        pumpUntil { requireNotNull(manager).isInitialized && requireNotNull(manager).isIdle }
    }
    private fun put(id: String, key: String, state: Int, data: ByteArray = byteArrayOf(9, 0, 8),
        start: Long = 123, update: Long = 456): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/$id"))
            .setCustomCacheKey(key).setData(data).build()
        val progress = DownloadProgress().apply { bytesDownloaded = 2; percentDownloaded = 50f }
        return Download(request, state, start, update, 4, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE, progress)
            .also(index::putDownload)
    }
    private fun rows(): List<Download> = ArrayList<Download>().also { result ->
        index.getDownloads().use { while (it.moveToNext()) result += it.download }
    }
    private fun seed(key: String, partial: Boolean = false) {
        val bytes = if (partial) payload.copyOf(2) else payload
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes); cache.commitFile(file, bytes.size.toLong())
            cache.applyContentMetadataMutations(key,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        } finally { cache.releaseHoleSpan(hole) }
    }
    private fun pumpUntil(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(1)
        } while (System.nanoTime() < until)
        fail("Timed out waiting for the real manager's index/task boundary")
    }
}
