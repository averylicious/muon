package dev.avery.muon

import android.net.Uri
import android.os.Looper
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

    private fun start(downloadIndex: WritableDownloadIndex, factory: DownloaderFactory) {
        manager = DownloadManager(RuntimeEnvironment.getApplication(), downloadIndex, factory)
        pumpUntil { requireNotNull(manager).isInitialized && requireNotNull(manager).isIdle }
    }
    private fun put(id: String, key: String, state: Int): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/$id"))
            .setCustomCacheKey(key).setData(byteArrayOf(9, 0, 8)).build()
        val progress = DownloadProgress().apply { bytesDownloaded = 2; percentDownloaded = 50f }
        return Download(request, state, 123, 456, 4, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE, progress)
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
