package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteConstraintException
import androidx.media3.exoplayer.offline.Download
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
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
class SavedCatalogTest {
    @get:Rule val folders = TemporaryFolder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var context: Context
    private lateinit var catalog: SavedCatalog
    private fun <T> io(block: () -> T): T = worker.submit<T> { block() }.get(10, TimeUnit.SECONDS)

    @Before fun setUp() {
        val root = folders.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir(): File = root
        }
        catalog = io { SavedCatalog.open(context) }
    }
    @After fun tearDown() {
        try { io { catalog.close() } } finally { worker.shutdownNow() }
    }

    @Test fun everyCopyPaginatesExactlyOnceInTheExistingExactReadingOrder(): Unit = io {
        val entries = (0 until 237).map { n -> entry("saved/$n", "Title ${n % 9}",
            if (n % 2 == 0) SavedShelf.Card else SavedShelf.Phone) }.reversed()
        val expected = sortSaved(entries).map { it.ref }
        val generation = catalog.rebuild(entries)
        assertEquals(237L, generation.count)
        val actual = (0L until generation.count step 50L).flatMap { catalog.page(generation, it) }
        assertEquals(expected, actual)
        assertEquals(actual.size, actual.toSet().size)
        assertTrue(catalog.page(generation, generation.count).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { catalog.page(generation, 0, 51) }
        assertThrows(IllegalArgumentException::class.java) { catalog.page(generation, -1) }
    }

    @Test fun unicodeLongPrefixesUnknownTitlesAndHandleTiesMatchKotlinNotSqliteTextOrder(): Unit = io {
        val prefix = "a".repeat(256)
        val entries = listOf(entry("saved/z", prefix + "B"), entry("saved/a", prefix + "a"),
            entry("saved/emoji", "\uD83D\uDE00"), entry("saved/bmp", "\uE000"),
            entry("saved/dotted", "İ"), entry("saved/surrogate", "\uD800"),
            entry("saved/empty", ""), entry("saved/tie-a", "same"), entry("saved/tie-b", "SAME"),
            entry("saved/unknown", "ignored").copy(song = null),
            entry("saved/oversized", "ignored").copy(song = null, storedMetadataTooLarge = true))
        val generation = catalog.rebuild(entries)
        assertEquals(sortSaved(entries).map { it.ref }, catalog.page(generation, 0))
        assertArrayEquals(byteArrayOf(0xd8.toByte(), 0), savedCatalogSortKey("\uD800"))
        // Scalar/UTF-8 ordering would put E000 before the surrogate pair; Kotlin orders code units.
        assertTrue("\uD83D\uDE00" < "\uE000")
    }

    @Test fun aFailedEnumerationNeverPublishesAPrefixOrChangesThePreviousGeneration(): Unit = io {
        val kept = catalog.rebuild(listOf(entry("saved/kept", "Kept")))
        val failing = Iterable { sequence {
            yield(entry("saved/new", "New"))
            throw IOException("Fixture source disappeared")
        }.iterator() }
        assertThrows(IOException::class.java) { catalog.rebuild(failing) }
        assertEquals(kept, catalog.snapshot())
        assertEquals(listOf(entry("saved/kept", "Kept").ref), catalog.page(kept, 0))
    }

    @Test fun cancellationAndDuplicateLocatorsRollBackWithoutChangingOriginalEntries(): Unit = io {
        val original = entry("saved/original", "Original")
        val kept = catalog.rebuild(listOf(original))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            catalog.rebuild(listOf(entry("saved/a", "A"), entry("saved/b", "B"))) { ++checks == 2 }
        }
        assertEquals(kept, catalog.snapshot())
        assertThrows(SQLiteConstraintException::class.java) { catalog.rebuild(listOf(original, original)) }
        assertEquals(listOf(original.ref), catalog.page(kept, 0))
        assertEquals("Original", original.song?.title)
    }

    @Test fun staleGenerationIsRefusedAndTheCommittedGenerationSurvivesReopening(): Unit = io {
        val old = catalog.rebuild(listOf(entry("saved/old", "Old")))
        val newer = catalog.rebuild(listOf(entry("saved/new", "New")))
        assertThrows(SavedCatalogStale::class.java) { catalog.page(old, 0) }
        catalog.close()
        catalog = SavedCatalog.open(context)
        assertEquals(newer, catalog.snapshot())
        assertEquals(listOf(entry("saved/new", "New").ref), catalog.page(newer, 0))
        assertTrue(File(context.noBackupFilesDir, "saved-catalog-v1.db").isFile)
    }

    @Test fun catalogIoIsRefusedOnMainBeforeItCanReadOrWrite() {
        val expected = io { catalog.snapshot() }
        assertThrows(IllegalStateException::class.java) { catalog.snapshot() }
        assertThrows(IllegalStateException::class.java) { catalog.page(expected, 0) }
        assertThrows(IllegalStateException::class.java) { catalog.rebuild(emptyList()) }
        assertThrows(IllegalStateException::class.java) { SavedCatalog.open(context) }
        assertEquals(expected, io { catalog.snapshot() })
    }

    private fun entry(id: String, title: String, shelf: SavedShelf = SavedShelf.Phone): SavedEntry =
        SavedEntry(requireNotNull(SavedRef.download(shelf, id, id)),
            TauonTrack(42, title, "Artist", "Album", 1000, true, false), "http://127.0.0.1:7814",
            Download.STATE_COMPLETED, SavedCoverage.Full, 4, false, true)
}
