package dev.avery.muon

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowEnvironment
import java.io.File

/**
 * #179 S1 wiring with real disposable caches and indexes: the production route, listing, count and
 * remove decisions against a card shelf whose presence comes from [cardPresent]. Mount state comes from
 * Robolectric's ShadowEnvironment (4.16.1: getExternalStorageState(File) returns the state registered
 * for a containing path, null otherwise), not from Android's StorageManager, so what a real ROM reports
 * during removal stays a device question. "Removal" here is renaming the card's cache folder away and
 * marking it unmounted; no real card, mount event, network or downloader is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CardAvailabilityRouteTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private lateinit var cardRoot: File
    private lateinit var cardDownloads: File
    private var presenceChecks = 0
    private val shelves = mutableListOf<Shelf>()
    private val indexes = mutableMapOf<Shelf, GatedIndex>()
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previousStore: Any? = null
    private val app get() = RuntimeEnvironment.getApplication()
    private val endpoint = ServerEndpoint.parse("http://192.168.1.20:7814")
    private val onCard = TauonTrack(7, "On the card", "Artist", "Album", 180_000, true, false)
    private val onPhone = TauonTrack(8, "On the phone", "Artist", "Album", 180_000, true, false)
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(app)
        phone = shelf(folders.newFolder("phone"), "", MuonDownloadService::class.java) { true }
        cardRoot = folders.newFolder("card_root")
        cardDownloads = File(cardRoot, "downloads")
        ShadowEnvironment.setExternalStorageState(cardRoot, Environment.MEDIA_MOUNTED)
        card = shelf(cardDownloads, "card", MuonCardDownloadService::class.java) { presenceChecks++; cardPresent(cardRoot) }
        complete(card, onCard)
        complete(phone, onPhone)
        previousStore = storeField.get(null)
        val store = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, app.getSharedPreferences("card-availability", Context.MODE_PRIVATE),
            database, {}, {})
        store.card = card
        store.cardFolder = cardRoot
        storeField.set(null, store)
    }

    @After fun tearDown() {
        try {
            storeField.set(null, previousStore)
            shelves.asReversed().forEach { it.manager.release(); it.cache.release() }
        } finally { database.close() }
    }

    @Test fun cardPresentFollowsTheRegisteredMountState() {
        val root = folders.newFolder("states")
        ShadowEnvironment.setExternalStorageState(root, Environment.MEDIA_MOUNTED)
        assertTrue(cardPresent(root))
        ShadowEnvironment.setExternalStorageState(root, Environment.MEDIA_MOUNTED_READ_ONLY)
        assertFalse(cardPresent(root))
        ShadowEnvironment.setExternalStorageState(root, Environment.MEDIA_UNMOUNTED)
        assertFalse(cardPresent(root))
        ShadowEnvironment.setExternalStorageState(root, Environment.MEDIA_REMOVED)
        assertFalse(cardPresent(root))
        // Mounted, but Muon's folder is not there: not present, and not created to find out.
        ShadowEnvironment.setExternalStorageState(root, Environment.MEDIA_MOUNTED)
        val missing = File(root, "Android/data/dev.avery.muon/files")
        assertFalse(cardPresent(missing))
        assertFalse(missing.exists())
        // No state registered at all (the shadow answers null): not present.
        assertFalse(cardPresent(folders.newFolder("unregistered")))
    }

    @Test fun anAvailableCardsSavedCopyRoutesToItAndIsListed() {
        val entries = OfflineStore.savedEntries(app)
        assertTrue(presenceChecks > 0)
        assertEquals(setOf(onCard, onPhone), entries.mapNotNull { it.song }.toSet())
        val onTheCard = entries.single { it.song == onCard }
        assertEquals(SavedShelf.Card, onTheCard.ref.shelf)
        val route = routeOfflineRequest(saved(onTheCard), phone, card)
        assertSame(card, route.first)
        assertEquals(id(onCard), route.second.key)
        assertEquals(1, OfflineStore.downloadsOn(app, card = true))
        // A live song never routes to a copy, on either shelf (#213).
        val live = stream(onCard)
        assertSame(phone, routeOfflineRequest(live, phone, card).first)
        assertSame(live, routeOfflineRequest(live, phone, card).second)
    }

    @Test fun anUnavailableCardIsSkippedWhileThePhoneStillWorks() {
        val onTheCard = OfflineStore.savedEntries(app).single { it.song == onCard }
        remove()
        // Not listed, and its handle is refused by the reader rather than read from missing storage.
        val listed = OfflineStore.savedEntries(app)
        assertEquals(listOf(onPhone), listed.mapNotNull { it.song })
        val source = OfflineDataSource { routeOfflineRequest(it, phone, card) }
        try {
            assertThrows(java.io.IOException::class.java) { source.open(saved(onTheCard)) }
        } finally { source.close() }
        // The phone's own copy still routes.
        val phoneEntry = listed.single()
        assertSame(phone, routeOfflineRequest(saved(phoneEntry), phone, card).first)
        // Unavailable is not empty.
        assertNull(OfflineStore.downloadsOn(app, card = true))
        assertEquals(1, OfflineStore.downloadsOn(app, card = false))
        // These decisions read neither the card's cache nor its index into changing: the record is still
        // there, and once the folder is back the cache still maps the span (a stale-span scan while the
        // files were gone would have dropped it).
        assertTrue(card.completed(id(onCard)))
        restore()
        assertArrayEquals(bytes, requireNotNull(card.cache.getCachedSpans(id(onCard)).first().file).readBytes())
        assertSame(card, routeOfflineRequest(saved(onTheCard), phone, card).first)
    }

    @Test fun removingWithTheCardUnavailableSendsNothingToTheCardService() {
        val entries = OfflineStore.savedEntries(app)
        remove()
        val started = shadowOf(app)
        while (started.nextStartedService != null) Unit
        assertEquals(OfflineStore.SavedRemoval.Unavailable,
            OfflineStore.removeSavedNow(app, entries.single { it.song == onCard }.ref))
        assertEquals(OfflineStore.SavedRemoval.Sent,
            OfflineStore.removeSavedNow(app, entries.single { it.song == onPhone }.ref))
        val services = generateSequence { started.nextStartedService }.map { it.component?.className }.toList()
        assertEquals(listOf(MuonDownloadService::class.java.name), services)
        assertTrue(card.completed(id(onCard)))
    }

    /**
     * #401: the Settings move count runs its real index read on a worker. A caller cancelled while that read
     * is held does not finish, and does not release the gate, until the read actually returns and closes its
     * cursor; meanwhile another request is refused without reading. Afterwards a new count runs.
     */
    @Test fun aCancelledMoveCountHoldsItsGateUntilTheHeldReadReturns() {
        val index = indexes.getValue(card)
        val release = CountDownLatch(1)
        index.hold = release
        val callerJob = SupervisorJob()
        val caller = CoroutineScope(callerJob + Dispatchers.Default)
        try {
            val first = caller.async { OfflineStore.moveOfferCount(card = true) }
            assertTrue(index.entered.tryAcquire(10, TimeUnit.SECONDS))
            first.cancel()
            assertFalse(first.isCompleted)
            assertEquals(0, index.returned.get())
            assertSame(OfflineStore.MoveOfferCount.Busy, runBlocking { OfflineStore.moveOfferCount(card = true) })
            // The gate is process-wide: a count of the other shelf does not overlap it either.
            assertSame(OfflineStore.MoveOfferCount.Busy, runBlocking { OfflineStore.moveOfferCount(card = false) })
            assertEquals(1, index.reads.get())
            assertEquals(0, indexes.getValue(phone).reads.get())
            release.countDown()
            runBlocking { withTimeout(10_000) { first.join() } }
            assertTrue(first.isCancelled)
            assertEquals(1, index.returned.get())
            assertEquals(1, index.closed.get())
            index.hold = null
            val callerThread = Thread.currentThread()
            assertEquals(OfflineStore.MoveOfferCount.Counted(1), runBlocking { OfflineStore.moveOfferCount(card = true) })
            assertEquals(2, index.reads.get())
            assertEquals(2, index.closed.get())
            // Read off the calling (here, Robolectric's main) thread.
            assertNotSame(callerThread, index.thread)
            assertEquals(false, index.onMain)
        } finally {
            release.countDown()
            caller.cancel()
            runBlocking { withTimeout(10_000) { callerJob.join() } }
        }
    }

    @Test fun aFailedOrUnavailableMoveCountIsNotZeroCopies() {
        val index = indexes.getValue(card)
        index.fail = true
        assertSame(OfflineStore.MoveOfferCount.Unavailable, runBlocking { OfflineStore.moveOfferCount(card = true) })
        assertEquals(1, index.returned.get())
        assertNull(OfflineStore.downloadsOn(app, card = true))
        index.fail = false
        // A failed read released the gate; a real empty shelf counts zero.
        (card.manager.downloadIndex as WritableDownloadIndex).removeDownload(id(onCard))
        assertEquals(OfflineStore.MoveOfferCount.Counted(0), runBlocking { OfflineStore.moveOfferCount(card = true) })
        assertEquals(0, OfflineStore.downloadsOn(app, card = true))
        // Four reads: two failed before opening a cursor, and both cursors that were opened closed.
        assertEquals(4, index.reads.get())
        assertEquals(4, index.returned.get())
        assertEquals(2, index.closed.get())
        // An unavailable card is not read at all.
        remove()
        val reads = index.reads.get()
        assertSame(OfflineStore.MoveOfferCount.Unavailable, runBlocking { OfflineStore.moveOfferCount(card = true) })
        assertEquals(reads, index.reads.get())
        val phoneReads = indexes.getValue(phone).reads.get()
        assertSame(OfflineStore.MoveOfferCount.Unavailable, runBlocking { OfflineStore.moveOfferCount(card = false) })
        assertEquals("No phone count is needed when its destination is unavailable", phoneReads, indexes.getValue(phone).reads.get())
        restore()
    }

    @Test fun aMoveCountIsNotReportedForAShelfReplacedDuringTheRead() {
        // Card can be either source or destination; a phone count must not offer a move to a card
        // that disappeared or was replaced while that phone cursor was being read.
        for (sourceCard in listOf(true, false)) {
            val index = indexes.getValue(if (sourceCard) card else phone)
            val release = CountDownLatch(1)
            index.hold = release
            val store = storeField.get(null) as OfflineStore.Store
            val callerJob = SupervisorJob()
            val caller = CoroutineScope(callerJob + Dispatchers.Default)
            try {
                val pending = caller.async { OfflineStore.moveOfferCount(card = sourceCard) }
                assertTrue(index.entered.tryAcquire(10, TimeUnit.SECONDS))
                store.card = null
                release.countDown()
                assertSame(OfflineStore.MoveOfferCount.Unavailable,
                    runBlocking { withTimeout(10_000) { pending.await() } })
                assertEquals(1, index.closed.get())
            } finally {
                release.countDown()
                caller.cancel()
                runBlocking { withTimeout(10_000) { callerJob.join() } }
                store.card = card
                index.hold = null
            }
        }
    }

    private fun saved(entry: SavedEntry): DataSpec = DataSpec.Builder().setUri(Uri.parse(entry.ref.handle)).build()

    private fun id(track: TauonTrack) = downloadId(endpoint.origin, track.id)

    private fun stream(track: TauonTrack): DataSpec =
        DataSpec.Builder().setUri(Uri.parse(endpoint.url("/api1/file/${track.id}"))).build()

    /** The card going: its cache folder moved away and its volume reported unmounted. */
    private fun remove() {
        assertTrue(cardDownloads.renameTo(File(folders.root, "away")))
        ShadowEnvironment.setExternalStorageState(cardRoot, Environment.MEDIA_UNMOUNTED)
    }

    private fun restore() {
        assertTrue(File(folders.root, "away").renameTo(cardDownloads))
        ShadowEnvironment.setExternalStorageState(cardRoot, Environment.MEDIA_MOUNTED)
    }

    private fun shelf(folder: File, name: String, service: Class<out androidx.media3.exoplayer.offline.DownloadService>,
        present: () -> Boolean): Shelf {
        val cache = SimpleCache(folder, NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val index = GatedIndex(DefaultDownloadIndex(database, name))
        return Shelf(cache, DownloadManager(app, index,
            DownloaderFactory { error("Fixture must not start a downloader/network") }), service, present)
            .also(shelves::add).also { indexes[it] = index }
    }

    /**
     * The real index, with its completed-row reads (the Settings move count's query) observable and, when
     * armed, held on whichever thread runs them or failed. Other reads, such as the manager's own, pass through.
     */
    private class GatedIndex(private val inner: DefaultDownloadIndex) : WritableDownloadIndex by inner {
        @Volatile var hold: CountDownLatch? = null
        @Volatile var fail = false
        @Volatile var thread: Thread? = null
        @Volatile var onMain: Boolean? = null
        val entered = Semaphore(0)
        val reads = AtomicInteger()
        val returned = AtomicInteger()
        val closed = AtomicInteger()
        override fun getDownloads(vararg states: Int): DownloadCursor {
            if (!states.contentEquals(intArrayOf(Download.STATE_COMPLETED))) return inner.getDownloads(*states)
            reads.incrementAndGet()
            thread = Thread.currentThread()
            onMain = Looper.myLooper() == Looper.getMainLooper()
            entered.release()
            try {
                // Not interruptible by coroutine cancellation: it returns only when released.
                hold?.let { check(it.await(10, TimeUnit.SECONDS)) { "Held count was never released" } }
                if (fail) throw IOException("Fixture index failure")
                return ClosingCursor(inner.getDownloads(*states), closed)
            } finally { returned.incrementAndGet() }
        }
    }

    private class ClosingCursor(private val inner: DownloadCursor, private val closed: AtomicInteger) : DownloadCursor by inner {
        override fun close() { closed.incrementAndGet(); inner.close() }
    }

    private fun complete(shelf: Shelf, track: TauonTrack) {
        val id = id(track)
        val hole = shelf.cache.startReadWrite(id, 0, bytes.size.toLong())
        try {
            val file = shelf.cache.startFile(id, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            shelf.cache.commitFile(file, bytes.size.toLong())
            shelf.cache.applyContentMetadataMutations(id,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        } finally { shelf.cache.releaseHoleSpan(hole) }
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/${track.id}")))
            .setCustomCacheKey(id).setData(encodeSong(track)).build()
        (shelf.manager.downloadIndex as WritableDownloadIndex).putDownload(Download(request, Download.STATE_COMPLETED,
            1, 1, bytes.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }
}
