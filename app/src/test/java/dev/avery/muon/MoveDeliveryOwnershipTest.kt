package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Real move, both services/manager callbacks and native indexes/caches; network-free fixture downloader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MoveDeliveryOwnershipTest {
    @get:Rule val folders = TemporaryFolder()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private lateinit var store: OfflineStore.Store
    private lateinit var phoneService: ServiceController<MuonDownloadService>
    private lateinit var cardService: ServiceController<MuonCardDownloadService>
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val moverField = OfflineStore::class.java.getDeclaredField("mover").apply { isAccessible = true }
    private val saverField = OfflineStore::class.java.getDeclaredField("saver").apply { isAccessible = true }
    private var previous: Any? = null
    private val id = "saved/move-fixture"
    private val bytes = ByteArray(4096) { (it % 251).toByte() }
    private var startId = 0

    @Before fun setUp() {
        DownloadService.clearDownloadManagerHelpers()
        database = StandaloneDatabaseProvider(app)
        phone = shelf("phone", MuonDownloadService::class.java)
        card = shelf("card", MuonCardDownloadService::class.java)
        complete(phone, id)
        store = newStore()
        previous = storeField.get(null)
        storeField.set(null, store)
        phoneService = Robolectric.buildService(MuonDownloadService::class.java).create()
        cardService = Robolectric.buildService(MuonCardDownloadService::class.java).create()
        val watch = OfflineStore::class.java.getDeclaredMethod("watch", Context::class.java, Shelf::class.java,
            OfflineStore.Store::class.java, Handler::class.java).apply { isAccessible = true }
        for (shelf in listOf(phone, card)) watch.invoke(OfflineStore, app, shelf, store, Handler(Looper.getMainLooper()))
        settle(phone); settle(card)
        commands()
    }

    @After fun tearDown() {
        try {
            store.moves.invalidateAll()
            await(moverField); await(saverField)
            shadowOf(Looper.getMainLooper()).idle()
            phoneService.destroy(); cardService.destroy()
            storeField.set(null, previous)
            for (shelf in listOf(card, phone)) { shelf.manager.release(); shelf.cache.release() }
            DownloadService.clearDownloadManagerHelpers()
        } finally { database.close() }
    }

    @Test fun ownedAddAndRemoveReachBothServicesAndAcknowledgmentReleasesTheMove() {
        val add = movedAdd()
        deliver(cardService, add); settle(card); await(saverField)
        val remove = commands().single { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
        assertTrue(store.moves.hasPending) // Sending the intent is not acknowledgment.
        assertFalse(store.moves.removalInFlight)
        deliver(phoneService, remove)
        assertTrue(store.moves.removalInFlight)
        // A target deletion cannot overtake the already accepted removal of its original.
        assertEquals(OfflineStore.SavedRemoval.Busy, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Card, id, id))))
        assertFalse(store.moves.acknowledgeRemoval(card, request(id)))
        assertFalse(store.moves.acknowledgeRemoval(phone, request(id).copyWithId("wrong-copy")))
        store.moves.invalidateAll() // Cannot undo a removal already admitted to Media3.
        assertTrue(store.moves.hasPending)
        assertTrue(store.moves.removalInFlight)
        settle(phone); await(saverField)
        assertFalse(store.moves.hasPending)
        assertNull(phone.manager.downloadIndex.getDownload(id))
        assertTrue(phone.cache.getCachedSpans(id).isEmpty())
        assertEquals(Download.STATE_COMPLETED, card.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(card.cache.isCached(id, 0, bytes.size.toLong()))
        // Neither internal command can be replayed after acknowledgment.
        deliver(cardService, add); deliver(phoneService, remove)
        settle(phone); settle(card)
        assertEquals(Download.STATE_COMPLETED, card.manager.downloadIndex.getDownload(id)?.state)
    }

    @Test fun userRemovalBeforeDeliveryInvalidatesTheOldAddInsteadOfResurrectingIt() {
        val add = movedAdd()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, id, id))))
        val remove = commands().single { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
        deliver(phoneService, remove); settle(phone)
        deliver(cardService, add); settle(card)
        assertNull(phone.manager.downloadIndex.getDownload(id))
        assertNull(card.manager.downloadIndex.getDownload(id))
        assertFalse(store.moves.hasPending)
        assertTrue("Unindexed copied bytes are preserved, not relabelled", card.cache.isCached(id, 0, bytes.size.toLong()))
    }

    @Test fun processLocalReceiptLossRefusesAnOldQueuedAddAndAnOldQueuedRemoval() {
        val add = movedAdd()
        // Models the ownership registry after restart, without pretending to reboot a device/cache here.
        storeField.set(null, newStore())
        deliver(cardService, add); settle(card)
        assertNull(card.manager.downloadIndex.getDownload(id))
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(id)?.state)
        storeField.set(null, store)
        deliver(cardService, add); settle(card); await(saverField)
        val remove = commands().single { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
        storeField.set(null, newStore())
        deliver(phoneService, remove); settle(phone)
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertTrue(card.cache.isCached(id, 0, bytes.size.toLong()))
        storeField.set(null, store)
    }

    @Test fun changedSourceRecordRefusesTheQueuedAutomaticRemoval() {
        val remove = queuedRemove()
        val replacement = DownloadRequest.Builder(id, Uri.parse("fixture://replacement")).setCustomCacheKey(id).build()
        // Index injection models a rebound record; normal new saves use unique keys and cannot do this.
        (phone.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(replacement,
            Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(), 0, 0))
        deliver(phoneService, remove); settle(phone)
        assertEquals(replacement, phone.manager.downloadIndex.getDownload(id)?.request)
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertFalse(store.moves.hasPending)
    }

    @Test fun aCommandBetweenVerificationAndRemovalInvalidatesItsEpoch() {
        val remove = queuedRemove()
        deliver(phoneService, DownloadService.buildSetStopReasonIntent(app, MuonDownloadService::class.java,
            "another-entry", 9, false))
        settle(phone)
        deliver(phoneService, remove); settle(phone)
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(phone.cache.isCached(id, 0, bytes.size.toLong()))
        assertFalse(store.moves.hasPending)
    }

    @Test fun twoCopiesAreRemovedSeriallyOnlyAfterEachAcknowledgment() {
        val other = "saved/another-move-fixture"
        complete(phone, other)
        OfflineStore.move(app, true); await(moverField); shadowOf(Looper.getMainLooper()).idle()
        val adds = commands().filter { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
        assertEquals(2, adds.size)
        for (add in adds) deliver(cardService, add)
        settle(card); await(saverField)
        var removals = commands().filter { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
        assertEquals("Only one source removal may await delivery", 1, removals.size)
        deliver(phoneService, removals.single()); settle(phone); await(saverField)
        removals = commands().filter { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
        assertEquals(1, removals.size)
        deliver(phoneService, removals.single()); settle(phone); await(saverField)
        assertFalse(store.moves.hasPending)
        assertNull(phone.manager.downloadIndex.getDownload(id))
        assertNull(phone.manager.downloadIndex.getDownload(other))
        assertTrue(card.cache.isCached(id, 0, bytes.size.toLong()))
        assertTrue(card.cache.isCached(other, 0, bytes.size.toLong()))
    }

    private fun queuedRemove(): Intent {
        deliver(cardService, movedAdd()); settle(card); await(saverField)
        return commands().single { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD }
    }
    private fun movedAdd(): Intent {
        OfflineStore.move(app, true); await(moverField); shadowOf(Looper.getMainLooper()).idle()
        return commands().single { it.action == DownloadService.ACTION_ADD_DOWNLOAD }
    }
    private fun <T : DownloadService> deliver(service: ServiceController<T>, intent: Intent) {
        service.get().onStartCommand(intent, 0, ++startId)
    }
    private fun commands(): List<Intent> = buildList {
        val application = shadowOf(app)
        while (true) add(application.nextStartedService ?: break)
    }
    private fun await(field: java.lang.reflect.Field) {
        (field.get(OfflineStore) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
    }
    private fun settle(shelf: Shelf) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (shelf.manager.isInitialized && shelf.manager.isIdle) return
            check(System.nanoTime() < deadline) { "Manager did not settle" }
            Thread.sleep(5)
        }
    }
    private fun newStore() = OfflineStore.Store(phone, DownloadArt(folders.newFolder()), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
        app.getSharedPreferences("move-delivery-fixture", Context.MODE_PRIVATE), database, {}, {}).also { it.card = card }
    private fun shelf(name: String, service: Class<out DownloadService>): Shelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val factory = DownloaderFactory { request -> object : Downloader {
            override fun download(progressListener: Downloader.ProgressListener?) {
                val key = request.customCacheKey ?: request.id
                val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
                check(length > 0 && cache.isCached(key, 0, length)) { "No network in this fixture" }
                progressListener?.onProgress(length, length, 100f)
            }
            override fun cancel() = Unit
            override fun remove() { cache.removeResource(request.customCacheKey ?: request.id) }
        } }
        return Shelf(cache, DownloadManager(app, DefaultDownloadIndex(database, name), factory).apply {
            setRequirements(Requirements(0))
        }, service)
    }
    private fun request(id: String) = DownloadRequest.Builder(id, Uri.parse("fixture://audio")).setCustomCacheKey(id).build()
    private fun complete(shelf: Shelf, id: String) {
        val hole = requireNotNull(shelf.cache.startReadWrite(id, 0, bytes.size.toLong()))
        try {
            val file = shelf.cache.startFile(id, 0, bytes.size.toLong()); file.writeBytes(bytes)
            shelf.cache.commitFile(file, bytes.size.toLong())
        } finally { shelf.cache.releaseHoleSpan(hole) }
        shelf.cache.applyContentMetadataMutations(id,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        (shelf.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(request(id), Download.STATE_COMPLETED,
            0, 0, bytes.size.toLong(), 0, 0))
    }
}
