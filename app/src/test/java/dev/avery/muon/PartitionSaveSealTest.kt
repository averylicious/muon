@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real ProgressiveDownloader + native journal/cache + DownloadManager index. Sealing is prepared,
 * not wired to app services; tests do not establish production command/cover/removal admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionSaveSealTest {
    @get:Rule val folders = TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val fixtures = mutableListOf<Fixture>()
    private val managers = mutableListOf<DownloadManager>()
    private val payload = byteArrayOf(1, 3, 5, 7, 9)
    private val key = "saved/new-audio"
    private inner class Fixture {
        val root = folders.newFolder()
        val catalog = CachePartitionCatalog(root)
        val migration = CacheMigrationJournal(root)
        val saves = PartitionSaveJournal(root, create = true)
        var journalClosed = false
        val ticket = saves.begin(catalog.reserve(key))
        val budget = PartitionNativeBudget(1)
        val owner = PartitionNativeOwner(catalog, migration, "phone", { "phone" }, budget = budget, saves = saves)
        val opened = mutableListOf<Cache>()
        val pool = PartitionCacheLeases({ requested ->
            check(requested == key)
            when (saves.find(key)?.phase) {
                PartitionSavePhase.Reserved -> owner.openNewSave(ticket)
                PartitionSavePhase.Closed -> owner.openSaved(key)
                else -> error("No fresh/clean test opening authority")
            }.also(opened::add)
        }, capacity = 1)
        val factory = PartitionDownloadFactory(pool, DataSource.Factory { ByteArrayDataSource(payload) },
            { _, _, _ -> error("No remove command is admitted") }, sealNewSaves = true)
        fun closeJournal() { if (!journalClosed) { journalClosed = true; saves.close() } }
        fun audioBytes(): List<Pair<File, ByteArray>> = File(catalog.directory(ticket.allocation), "bytes")
            .walkTopDown().filter { it.isFile && it.name.endsWith(".exo") }.map { it to it.readBytes() }.toList()
    }
    private fun fixture() = Fixture().also(fixtures::add)
    @After fun close() {
        managers.asReversed().forEach { it.release() }
        fixtures.asReversed().forEach { f ->
            f.pool.close()
            f.opened.asReversed().forEach { cache ->
                try { cache.release() } catch (_: Throwable) {
                    // Test-only disposal AFTER assertions for the deliberately failed durable-close case.
                    // No production reset/retry API is exposed and its native permit remains quarantined.
                    val h = cache.javaClass.getDeclaredField("h").apply { isAccessible = true }.get(cache)
                    for (name in listOf("native", "metadata", "database")) runCatching {
                        when (val value = h.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(h)) {
                            is SimpleCache -> value.release()
                            is PartitionContentMetadata -> value.close()
                            is SQLiteDatabase -> value.close()
                        }
                    }
                }
            }
            f.closeJournal(); f.migration.close(); f.catalog.close()
        }
        database.close()
    }
    private fun request() = DownloadRequest.Builder(key, Uri.parse("http://127.0.0.1:7814/api1/fileopus/1"))
        .setCustomCacheKey(key).build()
    private fun manager(f: Fixture, closeJournalAtEnd: Boolean = false): Pair<DownloadManager, DefaultDownloadIndex> {
        val index = DefaultDownloadIndex(database, "sealed")
        val factory = if (!closeJournalAtEnd) f.factory else DownloaderFactory { request ->
            val delegate = f.factory.createDownloader(request)
            object : androidx.media3.exoplayer.offline.Downloader by delegate {
                override fun download(progress: androidx.media3.exoplayer.offline.Downloader.ProgressListener?) {
                    delegate.download { length, bytes, percent ->
                        if (bytes == payload.size.toLong()) f.closeJournal()
                        progress?.onProgress(length, bytes, percent)
                    }
                }
            }
        }
        val manager = DownloadManager(RuntimeEnvironment.getApplication(), index, factory).also(managers::add)
        manager.setRequirements(Requirements(0)); manager.minRetryCount = 0
        manager.resumeDownloads()
        return manager to index
    }
    private fun await(manager: DownloadManager, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (manager.isInitialized && manager.isIdle && ready()) return
            check(System.nanoTime() < deadline) { "Download manager did not reach expected terminal state" }
            Thread.sleep(5) // Yield to the actual Media3 handler/task threads; no timing claim.
        }
    }

    @Test fun managerCannotPublishCompletedBeforeTheActualOwnedJournalAndNativeClose() {
        val f = fixture(); val (manager, index) = manager(f)
        var completed = 0
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.state == Download.STATE_COMPLETED) {
                    assertEquals(PartitionSavePhase.Closed, f.saves.find(key)?.phase)
                    assertEquals(0, f.pool.resident); assertEquals(0, f.budget.resident)
                    completed++
                }
            }
        })
        manager.addDownload(request())
        await(manager) { index.getDownload(key)?.state == Download.STATE_COMPLETED }
        assertEquals(1, completed)
        assertEquals(request(), index.getDownload(key)?.request)
        assertArrayEquals(payload, f.audioBytes().single().second)
        f.owner.openSaved(key).let { cache ->
            assertEquals(SavedCoverage.Full, savedAudioState(cache, key).coverage)
            cache.release()
        }
        assertEquals(PartitionSavePhase.Closed, f.saves.find(key)?.phase)
        assertEquals(0, f.budget.resident)
        assertNull(f.migration.ready(key)) // Clean/completed new save still never fabricates migration Ready.
    }

    @Test fun failedDurableSealMakesTheActualManagerKeepAFailedRecordAndAllAudio() {
        val f = fixture(); val (manager, index) = manager(f, closeJournalAtEnd = true)
        var completed = false
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                if (download.state == Download.STATE_COMPLETED) completed = true
            }
        })
        manager.addDownload(request())
        await(manager) { index.getDownload(key)?.state == Download.STATE_FAILED }
        assertFalse(completed)
        assertEquals(request(), index.getDownload(key)?.request)
        assertArrayEquals(payload, f.audioBytes().single().second)
        assertEquals(1, f.pool.resident); assertEquals(1, f.budget.resident)
        PartitionSaveJournal(f.root).use { reopened ->
            assertEquals(PartitionSavePhase.Open, reopened.find(key)?.phase)
        }
        assertThrows(IOException::class.java) { f.pool.acquire(key) }
        assertNull(f.migration.ready(key))
    }

    @Test fun fullCoverageWithAnotherReaderFailsCompletionWithoutClosingOrQuarantiningThatReader() {
        val f = fixture(); var reader: PartitionCacheLeases.Lease? = null
        val task = f.factory.createDownloader(request())
        try {
            assertThrows(PartitionCacheBusy::class.java) {
                task.download { _, bytes, _ ->
                    if (bytes == payload.size.toLong() && reader == null) reader = f.pool.acquire(key)
                }
            }
            assertEquals(1, f.pool.active)
            assertEquals(SavedCoverage.Full, savedAudioState(requireNotNull(reader).cache, key).coverage)
            assertEquals(PartitionSavePhase.Open, f.saves.find(key)?.phase)
            reader!!.close(); reader = null
            task.download(null) // Known contention failure remains retryable; no lost/uncertain I/O.
            assertEquals(PartitionSavePhase.Closed, f.saves.find(key)?.phase)
            assertEquals(0, f.pool.resident); assertEquals(0, f.budget.resident)
            assertArrayEquals(payload, f.audioBytes().single().second)
        } finally { reader?.close() }
    }

    @Test fun incompleteDeclaredCoverageCannotSealOrLoseItsRetainedBytes() {
        val f = fixture(); val task = f.factory.createDownloader(request())
        assertThrows(IOException::class.java) {
            task.download { _, bytes, _ ->
                if (bytes == payload.size.toLong()) f.pool.acquire(key).use { pin ->
                    // Controlled metadata mismatch after the last actual payload read, before seal.
                    pin.cache.applyContentMetadataMutations(key,
                        ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 7))
                }
            }
        }
        assertEquals(0, f.pool.active)
        assertEquals(PartitionSavePhase.Open, f.saves.find(key)?.phase)
        f.pool.acquire(key).use { assertEquals(SavedCoverage.Partial, savedAudioState(it.cache, key).coverage) }
        f.pool.close()
        assertEquals(PartitionSavePhase.Closed, f.saves.find(key)?.phase)
        assertArrayEquals(payload, f.audioBytes().single().second)
        assertNull(f.migration.ready(key))
    }

    @Test fun sealedModeRefusesAliasesAndPartialRequestsBeforeOpeningAnyNativeCache() {
        val f = fixture()
        val alias = DownloadRequest.Builder("different-id", request().uri).setCustomCacheKey(key).build()
        val range = DownloadRequest.Builder(key, request().uri).setCustomCacheKey(key).setByteRange(0, 2).build()
        assertThrows(IOException::class.java) { f.factory.createDownloader(alias) }
        assertThrows(IOException::class.java) { f.factory.createDownloader(range) }
        assertEquals(PartitionSavePhase.Reserved, f.saves.find(key)?.phase)
        assertEquals(0, f.pool.resident); assertEquals(0, f.budget.resident)
        assertFalse(f.catalog.directory(f.ticket.allocation).exists())
    }
}
