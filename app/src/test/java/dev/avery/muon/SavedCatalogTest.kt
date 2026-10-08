package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.ContentMetadataMutations
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

    @Test fun streamingRealNativeIndexesAndCacheMatchesInventoryAcrossHiddenUnknownAndPlayedCopies(): Unit = io {
        val database = StandaloneDatabaseProvider(context)
        val phone = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        val card = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        try {
            phone.checkInitialization(); card.checkInitialization()
            val index = DefaultDownloadIndex(database, "catalog_phone")
            val cardIndex = DefaultDownloadIndex(database, "catalog_card")
            fun put(target: DefaultDownloadIndex, id: String, key: String, state: Int, data: ByteArray): Download {
                val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/42"))
                    .setCustomCacheKey(key).setData(data).build()
                return Download(request, state, 1, 2, 4,
                    if (state == Download.STATE_STOPPED) RETAINED_STOP_REASON else Download.STOP_REASON_NONE,
                    if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE)
                    .also(target::putDownload)
            }
            val song = encodeSong(requireNotNull(entry("saved/sample", "Same song").song))
            val original = put(index, "saved/complete", "saved/complete", Download.STATE_COMPLETED, song)
            seed(phone, original.request.id)
            put(index, "hidden/" + "x".repeat(1400), original.request.id, Download.STATE_FAILED, ByteArray(256 * 1024))
            put(index, "saved/queued", "saved/queued", Download.STATE_QUEUED, song); seed(phone, "saved/queued", 2)
            put(index, "saved/failed", "saved/failed", Download.STATE_FAILED, song)
            put(index, "saved/removing", "saved/removing", Download.STATE_REMOVING, song)
            put(index, "saved/retained", "saved/retained", Download.STATE_STOPPED, song); seed(phone, "saved/retained")
            put(index, "saved/oversized", "saved/oversized", Download.STATE_COMPLETED, ByteArray(256 * 1024))
            seed(phone, "saved/oversized")
            put(cardIndex, "saved/complete", "saved/complete", Download.STATE_COMPLETED, song); seed(card, "saved/complete")
            val played = PLAYED_PREFIX + "http://127.0.0.1:7814/99"
            seed(phone, played)
            phone.applyContentMetadataMutations(played, ContentMetadataMutations().set(SONG_METADATA, song))
            val partial = PLAYED_PREFIX + "http://127.0.0.1:7814/100"
            seed(phone, partial, 2)
            val claims = PlayedClaims.none()
            val expected = sortSaved(savedInventory(SavedShelf.Phone, index, phone, claims) { false } +
                savedInventory(SavedShelf.Card, cardIndex, card, null) { false })
            val generation = catalog.rebuildFrom({ emit ->
                forEachSavedEntry(SavedShelf.Phone, index, phone, claims, { false }, emit)
                forEachSavedEntry(SavedShelf.Card, cardIndex, card, null, { false }, emit)
            })
            assertEquals(expected.map { it.ref }, catalog.page(generation, 0))
            assertFalse(expected.single { it.ref.requestId == "saved/complete" && it.ref.shelf == SavedShelf.Phone }.removable)
            assertTrue(expected.any { it.storedMetadataTooLarge && it.displaySong == null })
            assertEquals(original.request, index.getDownload(original.request.id)?.request)
            assertTrue(phone.isCached(original.request.id, 0, 4))
            assertEquals(expected.size.toLong(), generation.count)
        } finally { try { phone.release(); card.release() } finally { database.close() } }
    }

    @Test fun aRealStreamingIndexFailureClosesItsCursorAndKeepsThePriorCatalog(): Unit = io {
        val database = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        try {
            cache.checkInitialization()
            val index = DefaultDownloadIndex(database, "catalog_fault")
            for (n in 0..2) {
                val id = "saved/$n"
                val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/42"))
                    .setCustomCacheKey(id).setData(encodeSong(requireNotNull(entry(id, "Song$n").song))).build()
                index.putDownload(Download(request, Download.STATE_COMPLETED, 1, 2, 4,
                    Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
                seed(cache, id)
            }
            val good = catalog.rebuild(listOf(entry("saved/kept", "Kept")))
            val opened = ArrayList<DownloadCursor>()
            var projected = 0
            val bad = object : WritableDownloadIndex by index {
                override fun getDownloads(vararg states: Int): DownloadCursor {
                    val actual = index.getDownloads(*states); opened += actual
                    var reads = 0
                    return object : DownloadCursor by actual {
                        override fun getDownload(): Download {
                            if (++reads == 5) throw IOException("Fixture projection failed after a row")
                            return actual.download
                        }
                    }
                }
            }
            assertThrows(IOException::class.java) {
                catalog.rebuildFrom({ emit -> forEachSavedEntry(SavedShelf.Phone, bad, cache, null, { false }) {
                    projected++; emit(it)
                } })
            }
            assertEquals(1, projected) // First three reads build ownership, fourth projects, fifth fails.
            assertTrue(opened.single().isClosed)
            assertEquals(good, catalog.snapshot())
            assertEquals(listOf(entry("saved/kept", "Kept").ref), catalog.page(good, 0))
            assertTrue((0..2).all { cache.isCached("saved/$it", 0, 4) && index.getDownload("saved/$it") != null })
        } finally { try { cache.release() } finally { database.close() } }
    }

    @Test fun reentrantReadersAndRebuildsCannotExposeThePartialGeneration(): Unit = io {
        val old = catalog.rebuild(listOf(entry("saved/kept", "Kept")))
        val current = catalog.rebuildFrom({ emit ->
            emit(entry("saved/new", "New"))
            assertThrows(IllegalStateException::class.java) { catalog.page(old, 0) }
            assertThrows(IllegalStateException::class.java) { catalog.snapshot() }
            assertThrows(IllegalStateException::class.java) { catalog.rebuild(emptyList()) }
            assertThrows(IllegalStateException::class.java) { catalog.close() }
        })
        assertEquals(listOf(entry("saved/new", "New").ref), catalog.page(current, 0))
        assertThrows(SavedCatalogStale::class.java) { catalog.page(old, 0) }
    }

    @Test fun anotherNativeConnectionPublishesOnlyCommittedGenerationsAndOldSnapshotsAreRefused(): Unit = io {
        val old = catalog.rebuild(listOf(entry("saved/kept", "Kept")))
        val other = SavedCatalog.open(context)
        try {
            assertEquals(listOf(entry("saved/kept", "Kept").ref), other.page(old, 0))
            val current = other.rebuild(listOf(entry("saved/new", "New")))
            assertThrows(SavedCatalogStale::class.java) { catalog.page(old, 0) }
            assertEquals(current, catalog.snapshot())
            assertEquals(listOf(entry("saved/new", "New").ref), catalog.page(current, 0))
        } finally { other.close() }
    }

    @Test fun anEscapedProjectionCannotWriteOutsideItsTransactionOrFromAnotherThread(): Unit = io {
        var escaped: ((SavedEntry) -> Unit)? = null
        val generation = catalog.rebuildFrom({ emit ->
            escaped = emit
            java.util.concurrent.CompletableFuture.runAsync {
                assertThrows(IllegalStateException::class.java) { emit(entry("saved/wrong-thread", "Wrong thread")) }
            }.get(5, TimeUnit.SECONDS)
            emit(entry("saved/kept", "Kept"))
        })
        assertThrows(IllegalStateException::class.java) { requireNotNull(escaped)(entry("saved/late", "Late")) }
        assertEquals(generation, catalog.snapshot())
        assertEquals(1L, generation.count)
        assertEquals(listOf(entry("saved/kept", "Kept").ref), catalog.page(generation, 0))
    }

    private fun seed(cache: SimpleCache, key: String, size: Int = 4) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, size.toLong()))
        try {
            val file = cache.startFile(key, 0, size.toLong()); file.writeBytes(ByteArray(size) { 7 })
            cache.commitFile(file, size.toLong())
            cache.applyContentMetadataMutations(key,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 4L))
        } finally { cache.releaseHoleSpan(hole) }
    }

    private fun entry(id: String, title: String, shelf: SavedShelf = SavedShelf.Phone): SavedEntry =
        SavedEntry(requireNotNull(SavedRef.download(shelf, id, id)),
            TauonTrack(42, title, "Artist", "Album", 1000, true, false), "http://127.0.0.1:7814",
            Download.STATE_COMPLETED, SavedCoverage.Full, 4, false, true)
}
