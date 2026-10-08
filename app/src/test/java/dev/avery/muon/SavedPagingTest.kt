package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import androidx.media3.exoplayer.offline.Download
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedPagingTest {
    @get:Rule val folders = TemporaryFolder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var context: Context
    private lateinit var paging: SavedPaging
    private var entries = listOf<SavedEntry>()
    private var emitted = 0
    private var failAfter: Int? = null
    private fun <T> io(block: () -> T): T = worker.submit<T> { block() }.get(20, TimeUnit.SECONDS)
    @Before fun setup() {
        val directory = folders.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = directory
        }
        paging = SavedPaging(context) { include, checkpoint, emit ->
            var count = 0
            for (entry in entries) {
                checkpoint()
                if (!include(entry.ref)) continue
                if (count++ == failAfter) throw IOException("Controlled projection failure")
                emitted++; emit(entry)
            }
        }
    }
    @After fun close() { worker.shutdownNow() }

    @Test fun allCopiesRemainReachableInExactOrderWithOnlyFiftyHydratedPerPage(): Unit = io {
        entries = (0..236).map(::entry).reversed()
        val loaded = paging.reload(0) {}
        assertEquals(237L, loaded.page.snapshot.count)
        assertEquals(237L, loaded.playable)
        assertEquals(50, loaded.page.entries.size)
        val output = mutableListOf<SavedEntry>()
        for (offset in 0L..236L step 50) {
            emitted = 0
            val page = paging.page(loaded.page.snapshot, offset) {}
            assertEquals(page.entries.size, emitted)
            assertTrue(page.entries.size <= SAVED_PAGE_SIZE)
            output += page.entries
        }
        assertEquals(sortSaved(entries), output)
        assertEquals(entries.size, output.map { it.ref }.toSet().size)
    }

    @Test fun lruRetainsAtMostFourPagesAndClearsAcrossGenerations() {
        val cache = SavedPageCache()
        val snapshot = SavedCatalogSnapshot(1, 350)
        val pages = (0..6).map { page -> SavedPage(snapshot, page * 50L, (0..49).map { entry(page * 50 + it) }) }
        pages.take(4).forEach(cache::put)
        assertEquals(200, cache.retainedRows)
        assertSame(pages[0], cache.get(snapshot, 0)) // Touch makes page50 oldest.
        cache.put(pages[4])
        assertNull(cache.get(snapshot, 50)); assertNotNull(cache.get(snapshot, 0))
        assertEquals(200, cache.retainedRows)
        cache.put(pages[5].copy(snapshot = snapshot.copy(generation = 2)))
        assertEquals(50, cache.retainedRows)
        assertNull(cache.get(snapshot, 0))
        cache.clear(); assertEquals(0, cache.retainedRows)
    }

    @Test fun failedAndCancelledRebuildsKeepThePreviousCatalogGeneration(): Unit = io {
        entries = (0..74).map(::entry)
        val original = paging.reload(50) {}
        entries = (100..200).map(::entry); failAfter = 3
        assertThrows(IOException::class.java) { paging.reload(0) {} }
        failAfter = null
        SavedCatalog.open(context).use { assertEquals(original.page.snapshot, it.snapshot()) }
        var checks = 0
        assertThrows(CancellationException::class.java) { paging.reload(0) { if (++checks == 8) throw CancellationException() } }
        SavedCatalog.open(context).use { assertEquals(original.page.snapshot, it.snapshot()) }
    }

    @Test fun removedRowAndStaleGenerationRefuseRatherThanShowAnotherCopy(): Unit = io {
        entries = (0..59).map(::entry)
        val old = paging.reload(0) {}
        entries = entries.drop(1)
        assertThrows(SavedCatalogStale::class.java) { paging.page(old.page.snapshot, 0) {} }
        val fresh = paging.reload(0) {}
        assertThrows(SavedCatalogStale::class.java) { paging.page(old.page.snapshot, 0) {} }
        assertEquals(59L, fresh.page.snapshot.count)
        assertEquals(50L, savedPageOffset(500, 59))
        assertEquals(0L, savedPageOffset(50, 0))
    }

    @Test fun fullQueueIncludesCopiesBeyondTheCurrentPageAndPreservesSelectedIndex(): Unit = io {
        entries = (0..136).map(::entry).reversed()
        val snapshot = paging.reload(100) {}.page.snapshot
        val selected = entries[20]
        val result = paging.playback(snapshot, selected.ref, false) {} as SavedPlaybackResult.Ready
        val expected = sortSaved(entries)
        assertEquals(expected.map { it.ref.handle }, result.plan.items.map { it.mediaId })
        assertEquals(expected.indexOf(selected), result.plan.startIndex)
    }

    @Test fun oversizedFullLibraryOffersTheChosenSingleCopyWithoutTruncation(): Unit = io {
        entries = (0..2050).map(::entry)
        val snapshot = paging.reload(0) {}.page.snapshot
        val selected = entries.last()
        val refusal = paging.playback(snapshot, selected.ref, false) {} as SavedPlaybackResult.TooLarge
        assertEquals(selected, refusal.entry)
        val single = paging.playback(snapshot, selected.ref, true) {} as SavedPlaybackResult.Ready
        assertEquals(listOf(selected.ref.handle), single.plan.items.map { it.mediaId })
        assertEquals(2051L, SavedCatalog.open(context).use { it.snapshot().count })
        assertEquals(2051, entries.size)
    }

    @Test fun staleOrIncompleteSingleSelectionNeverBuildsAReplacementQueue(): Unit = io {
        entries = listOf(entry(1))
        val snapshot = paging.reload(0) {}.page.snapshot
        entries = listOf(entries.single().copy(coverage = SavedCoverage.Partial))
        assertThrows(SavedCatalogStale::class.java) { paging.playback(snapshot, entries.single().ref, true) {} }
        entries = emptyList()
        assertThrows(SavedCatalogStale::class.java) { paging.playback(snapshot, entry(1).ref, false) {} }
    }

    @Test fun mostCommonOriginTiesPreserveSortedDisplayOrderNotInputOrHashOrder(): Unit = io {
        val earlier = entry(1).copy(from = "http://192.168.1.9:7814")
        val later = entry(2).copy(from = "http://192.168.1.2:7814")
        entries = listOf(later, earlier)
        assertEquals(earlier.from, paging.reload(0) {}.origin)
        entries = listOf(later, entry(3).copy(from = later.from), earlier)
        assertEquals(later.from, paging.reload(0) {}.origin)
    }

    private fun entry(id: Int) = SavedEntry(requireNotNull(SavedRef.download(SavedShelf.Phone,
        "saved/$id", "saved/$id")), TauonTrack(id.toLong(), "Song ${id.toString().padStart(5, '0')}",
        "Artist", "Album", 1000, true, false), "http://192.168.1.2:7814", Download.STATE_COMPLETED,
        SavedCoverage.Full, 10, false, true)
}
