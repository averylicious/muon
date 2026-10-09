package dev.avery.muon

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowToast
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * #253 checked-removal delivery through production paths: the actual OfflineStore.removeSavedNow/removeAllNow
 * producers and DownloadRemovalDelivery, commands delivered to the actual MuonDownloadService /
 * MuonCardDownloadService, which pass them to Media3's real DownloadService and DownloadManager over native
 * SQLite and disposable SimpleCaches. Removal is carried out by a fixture downloader that deletes the row's
 * real cached bytes, so acceptance, refusal and uncertainty can each be seen in the index and the cache. Captured
 * service starts are delivered by the test; Android's own start scheduling, network, phone and card are not used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DownloadRemovalDeliveryTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private lateinit var store: OfflineStore.Store
    private val shelves = mutableListOf<Shelf>()
    private val services = mutableListOf<ServiceController<*>>()
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previousStore: Any? = null
    private val app get() = RuntimeEnvironment.getApplication()
    private val payload = ByteArray(4096) { (it % 251).toByte() }
    private val unconfirmedSingle = "Muon couldn't confirm that copy's removal. Check Saved copies before trying again."
    private val unchangedSingle = "That copy wasn't removed and is unchanged: saved-copy work is busy. Try again when it finishes."

    @Before fun setUp() {
        DownloadService.clearDownloadManagerHelpers()
        database = StandaloneDatabaseProvider(app)
        phone = shelf("phone", MuonDownloadService::class.java)
        card = shelf("card", MuonCardDownloadService::class.java)
        store = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("removal-delivery-fixture", Context.MODE_PRIVATE), database, {}, {})
        store.card = card
        previousStore = storeField.get(null)
        storeField.set(null, store)
        awaitSettled(phone.manager)
        awaitSettled(card.manager)
    }

    @After fun tearDown() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            services.asReversed().forEach { it.destroy() }
            storeField.set(null, previousStore)
            shelves.asReversed().forEach { it.manager.release(); it.cache.release() }
            DownloadService.clearDownloadManagerHelpers()
        } finally { database.close() }
    }

    @Test fun aSelectionOverFourIsSentInAcknowledgedWindowsAndEveryRowIsRemovedByTheRealServices() {
        val onPhone = (1..5).map { "saved/phone-$it" }.onEach { complete(phone, it) }
        val onCard = (1..2).map { "saved/card-$it" }.onEach { complete(card, it) }
        val phoneService = phoneService()
        val cardService = cardService()
        drain()
        assertEquals(OfflineStore.RemoveAllPlan(7, 0), OfflineStore.removeAllNow(app))
        val windows = ArrayList<List<String>>()
        while (true) {
            val sent = drain()
            if (sent.isEmpty()) break
            check(windows.size < 10) { "Windows never ended" }
            assertTrue("At most the service's removal budget at once", sent.size <= DOWNLOAD_REMOVAL_COUNT)
            assertTrue(sent.all { it.action == DownloadService.ACTION_REMOVE_DOWNLOAD && it.hasExtra(REMOVAL_DELIVERY_TOKEN) })
            assertTrue("One shelf per window", sent.map { it.component?.className }.toSet().size == 1)
            assertTrue("Busy until every window is answered", store.removalDelivery.busy)
            windows += sent.map { it.getStringExtra(DownloadService.KEY_CONTENT_ID)!! }
            sent.forEachIndexed { i, intent ->
                val target = if (intent.component?.className == MuonCardDownloadService::class.java.name)
                    cardService.get() else phoneService.get()
                target.onStartCommand(intent, 0, i + 1)
            }
            awaitSettled(phone.manager)
            awaitSettled(card.manager)
        }
        // Phone rows first (census order within a shelf is the index's), then the card's in a window of its own.
        assertEquals(listOf(4, 1, 2), windows.map { it.size })
        assertEquals(onPhone.toSet(), (windows[0] + windows[1]).toSet())
        assertEquals(onCard.toSet(), windows[2].toSet())
        assertFalse(store.removalDelivery.busy)
        assertEquals("Removing 7 saved copies.", ShadowToast.getTextOfLatestToast())
        onPhone.forEach { assertNull(phone.manager.downloadIndex.getDownload(it)); assertTrue(phone.cache.getCachedSpans(it).isEmpty()) }
        onCard.forEach { assertNull(card.manager.downloadIndex.getDownload(it)); assertTrue(card.cache.getCachedSpans(it).isEmpty()) }
    }

    @Test fun aLastingRefusalEndsAfterOneWindowKeepsEveryOriginalAndALaterRetryRemovesThem() {
        val ids = (1..6).map { "saved/refused-$it" }.onEach { complete(phone, it) }
        phone.commands = DownloadCommandBudget(removalCapacity = 0)
        val service = phoneService()
        drain()
        assertEquals(OfflineStore.RemoveAllPlan(6, 0), OfflineStore.removeAllNow(app))
        val first = drain()
        assertEquals(DOWNLOAD_REMOVAL_COUNT, first.size)
        first.forEachIndexed { i, intent -> service.get().onStartCommand(intent, 0, i + 1) }
        awaitSettled(phone.manager)
        assertTrue("No further window after one with nothing accepted", drain().isEmpty())
        assertFalse(store.removalDelivery.busy)
        assertEquals("No saved copies were confirmed as being removed. 6 copies weren't removed and are unchanged. " +
            "Remove all again when current work finishes.", ShadowToast.getTextOfLatestToast())
        ids.forEach {
            assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(it)?.state)
            assertTrue(phone.cache.isCached(it, 0, payload.size.toLong()))
        }

        phone.commands = DownloadCommandBudget()
        assertEquals(OfflineStore.RemoveAllPlan(6, 0), OfflineStore.removeAllNow(app))
        deliverAll(service)
        ids.forEach { assertNull(phone.manager.downloadIndex.getDownload(it)) }
        assertEquals("Removing 6 saved copies.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun aPartlyRefusedWindowIsRetriedAfterTheManagerIsIdleUntilEveryRowIsRemoved() {
        val ids = (1..5).map { "saved/partial-$it" }.onEach { complete(phone, it) }
        phone.commands = DownloadCommandBudget(removalCapacity = 2)
        val service = phoneService()
        drain()
        assertEquals(OfflineStore.RemoveAllPlan(5, 0), OfflineStore.removeAllNow(app))
        assertEquals(3, deliverAll(service))
        ids.forEach {
            assertNull(phone.manager.downloadIndex.getDownload(it))
            assertTrue(phone.cache.getCachedSpans(it).isEmpty())
        }
        assertEquals("Removing 5 saved copies.", ShadowToast.getTextOfLatestToast())
    }

    @Test fun aServiceFailureAfterTheManagerRemovalIsUnconfirmedNotUnchanged() {
        val id = "saved/uncertain"
        complete(phone, id)
        phoneService()
        drain()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app, ref(SavedShelf.Phone, id)))
        val intent = drain().single()
        assertThrows(IllegalStateException::class.java) {
            OfflineStore.deliverCommand(app, intent, phone) { admitted ->
                assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, admitted?.action)
                phone.manager.removeDownload(id)
                error("Injected failure after the actual manager call")
            }
        }
        awaitSettled(phone.manager)
        assertFalse(store.removalDelivery.busy)
        assertEquals(unconfirmedSingle, ShadowToast.getTextOfLatestToast())
        assertNull("The manager did remove it: reporting it unchanged would have been false",
            phone.manager.downloadIndex.getDownload(id))
    }

    @Test fun onlyTheExactTokenShelfAndIdClaimARemovalAndCopiesOrReplaysChangeNothing() {
        val id = "saved/exact"
        complete(phone, id)
        complete(card, id)
        complete(phone, "saved/other")
        val service = phoneService()
        drain()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app, ref(SavedShelf.Phone, id)))
        val intent = drain().single()
        fun refusedCopy(copy: Intent, shelf: Shelf) {
            OfflineStore.deliverCommand(app, copy, shelf) { admitted -> assertEquals(DownloadService.ACTION_INIT, admitted?.action); 0 }
        }
        refusedCopy(intent, card) // Same ID on the other shelf.
        refusedCopy(Intent(intent).putExtra(DownloadService.KEY_CONTENT_ID, "saved/other"), phone)
        refusedCopy(Intent(intent).putExtra(REMOVAL_DELIVERY_TOKEN, "unknown"), phone)
        refusedCopy(Intent(intent).putExtra(OfflineStore.MOVE_COMMAND_TOKEN, "move"), phone)
        refusedCopy(Intent(intent).setAction(DownloadService.ACTION_ADD_DOWNLOAD), phone)
        awaitSettled(phone.manager)
        assertTrue("None of those consumed the real command", store.removalDelivery.busy)
        listOf(id, "saved/other").forEach { assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(it)?.state) }

        service.get().onStartCommand(intent, 0, 1)
        awaitSettled(phone.manager)
        assertFalse(store.removalDelivery.busy)
        assertNull(phone.manager.downloadIndex.getDownload(id))
        assertEquals(Download.STATE_COMPLETED, card.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(card.cache.isCached(id, 0, payload.size.toLong()))

        // The acknowledged command replayed against a new row of the same name is not a second authority.
        complete(phone, id)
        service.get().onStartCommand(intent, 0, 2)
        awaitSettled(phone.manager)
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(phone.cache.isCached(id, 0, payload.size.toLong()))
    }

    @Test fun aTimedOutRemovalIsUnconfirmedItsLateCopyChangesNothingAndARetryWorks() {
        val id = "saved/late"
        complete(phone, id)
        val service = phoneService()
        drain()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app, ref(SavedShelf.Phone, id)))
        val late = drain().single()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(REMOVAL_DELIVERY_TIMEOUT_MS + 1))
        assertFalse(store.removalDelivery.busy)
        assertEquals(unconfirmedSingle, ShadowToast.getTextOfLatestToast())

        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app, ref(SavedShelf.Phone, id)))
        val retry = drain().single()
        service.get().onStartCommand(late, 0, 1) // A retired token: not the retry's.
        awaitSettled(phone.manager)
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(store.removalDelivery.busy)
        service.get().onStartCommand(retry, 0, 2)
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload(id))
        assertFalse(store.removalDelivery.busy)
    }

    @Test fun aCardRemovalRefusedByTheRetainedPhoneBindingIsReportedUnchanged() {
        store.card = null
        val fallback = cardService() // Created with no card: bound to the phone's manager for the process.
        store.card = card
        val id = "saved/on-card"
        complete(card, id)
        drain()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app, ref(SavedShelf.Card, id)))
        val intent = drain().single()
        assertEquals(MuonCardDownloadService::class.java.name, intent.component?.className)
        fallback.get().onStartCommand(intent, 0, 1)
        awaitSettled(phone.manager)
        assertFalse(store.removalDelivery.busy)
        assertEquals(unchangedSingle, ShadowToast.getTextOfLatestToast())
        assertEquals(Download.STATE_COMPLETED, card.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(card.cache.isCached(id, 0, payload.size.toLong()))
        assertNull(phone.manager.downloadIndex.getDownload(id))
    }

    @Test fun aPendingBatchMakesAnotherRemovalRemoveAllAndAMoveWaitWithoutChangingAnything() {
        complete(phone, "saved/first")
        complete(phone, "saved/second")
        phoneService()
        drain()
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app, ref(SavedShelf.Phone, "saved/first")))
        assertEquals(1, drain().size)
        assertEquals(OfflineStore.SavedRemoval.Waiting, OfflineStore.removeSavedNow(app, ref(SavedShelf.Phone, "saved/second")))
        assertEquals(OfflineStore.RemoveAllPlan(0, 0, busy = true), OfflineStore.removeAllNow(app))
        OfflineStore.move(app, toCard = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Saved copies are still being saved, removed or moved, so nothing was moved. " +
            "Try again when that has finished.", ShadowToast.getTextOfLatestToast())
        assertTrue(drain().isEmpty())
        assertTrue(card.cache.keys.isEmpty())
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload("saved/second")?.state)
    }

    @Test fun aStartTheSystemRefusesIsReportedUnchangedAndReleasesTheBatch() {
        val id = "saved/not-started"
        complete(phone, id)
        drain()
        val refusing = object : ContextWrapper(app) {
            override fun startService(service: Intent): ComponentName? = null
        }
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(refusing, ref(SavedShelf.Phone, id)))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(store.removalDelivery.busy)
        assertEquals(unchangedSingle, ShadowToast.getTextOfLatestToast())
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(id)?.state)
        assertTrue(phone.cache.isCached(id, 0, payload.size.toLong()))
    }

    // ---- fixture ----

    /** Delivers every captured start to its service window by window; returns how many windows there were. */
    private fun deliverAll(service: ServiceController<MuonDownloadService>): Int {
        var windows = 0
        while (true) {
            val sent = drain()
            if (sent.isEmpty()) return windows
            check(++windows < 10) { "Windows never ended" }
            assertTrue(sent.size <= DOWNLOAD_REMOVAL_COUNT)
            sent.forEachIndexed { i, intent -> service.get().onStartCommand(intent, 0, i + 1) }
            awaitSettled(phone.manager)
        }
    }

    private fun ref(shelf: SavedShelf, id: String) = requireNotNull(SavedRef.download(shelf, id, id))

    private fun phoneService(): ServiceController<MuonDownloadService> =
        Robolectric.buildService(MuonDownloadService::class.java).create().also(services::add)

    private fun cardService(): ServiceController<MuonCardDownloadService> =
        Robolectric.buildService(MuonCardDownloadService::class.java).create().also(services::add)

    private fun drain(): List<Intent> = buildList {
        val shadow = shadowOf(app)
        while (true) add(shadow.nextStartedService ?: break)
    }

    /** Bounded: pumps the main looper until the manager has loaded its index and processed every command. */
    private fun awaitSettled(manager: DownloadManager) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (manager.isInitialized && manager.isIdle) break
            check(System.nanoTime() < deadline) { "The manager did not settle" }
            Thread.sleep(5) // Yields to Media3's real internal handler thread; not a timing assertion.
        }
        shadowOf(Looper.getMainLooper()).idle() // Anything posted once it settled: the next window, notices.
    }

    private fun shelf(name: String, service: Class<out DownloadService>): Shelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        // Removal deletes the row's real bytes; downloading is never allowed.
        val downloaders = DownloaderFactory { request ->
            object : Downloader {
                override fun download(progressListener: Downloader.ProgressListener?) = error("No downloading in this fixture")
                override fun cancel() = Unit
                override fun remove() { cache.removeResource(request.customCacheKey ?: request.id) }
            }
        }
        return Shelf(cache, DownloadManager(app, DefaultDownloadIndex(database, name), downloaders), service).also(shelves::add)
    }

    private fun complete(shelf: Shelf, id: String) {
        val hole = requireNotNull(shelf.cache.startReadWrite(id, 0, payload.size.toLong()))
        try {
            val file = shelf.cache.startFile(id, 0, payload.size.toLong())
            file.writeBytes(payload)
            shelf.cache.commitFile(file, payload.size.toLong())
        } finally { shelf.cache.releaseHoleSpan(hole) }
        shelf.cache.applyContentMetadataMutations(id,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        val request = DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7")).setCustomCacheKey(id).build()
        (shelf.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(request, Download.STATE_COMPLETED,
            0L, 0L, payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }
}
