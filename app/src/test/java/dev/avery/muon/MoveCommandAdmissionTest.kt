package dev.avery.muon

import android.content.Context
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
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * #230 command admission through production paths: the actual OfflineStore.move admission and mover, and
 * commands delivered to the actual MuonDownloadService.onStartCommand, which passes them to Media3's real
 * DownloadService and DownloadManager over native SQLite and a disposable SimpleCache. Removal is carried out
 * by a fixture downloader that deletes the row's real cached bytes, so refusal and admission can both be seen
 * in the index and the cache. No network, phone, real card or downloading. The mover is held only by queuing
 * a test task on its real executor ahead of the move's own work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MoveCommandAdmissionTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private lateinit var card: Shelf
    private val shelves = mutableListOf<Shelf>()
    private val services = mutableListOf<ServiceController<MuonDownloadService>>()
    private val holds = mutableListOf<CountDownLatch>()
    @Volatile private var removalHold: CountDownLatch? = null
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val moverField = OfflineStore::class.java.getDeclaredField("mover").apply { isAccessible = true }
    private var previousStore: Any? = null
    private val app get() = RuntimeEnvironment.getApplication()
    private val kept = "http://192.168.1.20:7814/7"
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    @Before fun setUp() {
        DownloadService.clearDownloadManagerHelpers()
        database = StandaloneDatabaseProvider(app)
        phone = shelf("phone", MuonDownloadService::class.java)
        card = shelf("card", MuonCardDownloadService::class.java)
        complete(phone, kept)
        val store = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("move-admission-fixture", Context.MODE_PRIVATE), database, {}, {})
        store.card = card
        previousStore = storeField.get(null)
        storeField.set(null, store)
        awaitSettled(phone.manager)
        awaitSettled(card.manager)
    }

    @After fun tearDown() {
        try {
            holds.forEach { it.countDown() }
            awaitMover()
            shadowOf(Looper.getMainLooper()).idle()
            services.asReversed().forEach { it.destroy() }
            storeField.set(null, previousStore)
            shelves.asReversed().forEach { it.manager.release(); it.cache.release() }
            DownloadService.clearDownloadManagerHelpers()
        } finally { database.close() }
    }

    @Test fun aRemovalDeliveredDuringAMoveIsRefusedKeepingRowAndBytesThenAdmittedAfter() {
        val hold = holdMover()
        OfflineStore.move(app, toCard = true) // Admitted: both managers quiet. Its work waits behind the hold.
        // First creation of the service during the move: Media3 resumes the manager before any command.
        val service = service()
        service.get().onStartCommand(removal(), 0, 1)
        awaitSettled(phone.manager)
        assertEquals("A move is under way, so that change wasn't made. Try again when it finishes.",
            ShadowToast.getTextOfLatestToast())
        assertEquals("Refused before Media3: the row is kept", Download.STATE_COMPLETED,
            phone.manager.downloadIndex.getDownload(kept)?.state)
        assertTrue("and so are its bytes", phone.cache.isCached(kept, 0, payload.size.toLong()))

        hold.countDown()
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle() // The move's last step: release, then hand over.
        assertTrue("The move itself ran once admitted", card.cache.isCached(kept, 0, payload.size.toLong()))

        // Positive control: the same command delivered once the move has finished reaches the manager.
        service.get().onStartCommand(removal(), 0, 2)
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload(kept))
        assertTrue("The fixture can see a real removal", phone.cache.getCachedSpans(kept).isEmpty())
    }

    @Test fun aMoveIsRefusedWhileAManagerHasACommandPendingAndAdmittedOnceItIsProcessed() {
        val service = service()
        awaitSettled(phone.manager)
        // A harmless delivered command, still waiting on the manager's handler when the move is asked for.
        service.get().onStartCommand(DownloadService.buildPauseDownloadsIntent(app, MuonDownloadService::class.java, false), 0, 1)
        assertFalse(phone.manager.isIdle)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Saved copies are still being saved, removed or moved, so nothing was moved. " +
            "Try again when that has finished.", ShadowToast.getTextOfLatestToast())
        assertTrue("Nothing was copied", card.cache.getCachedSpans(kept).isEmpty())
        // No exclusion was taken: a removal is judged as usual (here: no such row), not refused as busy.
        assertEquals(OfflineStore.SavedRemoval.NotOwned,
            OfflineStore.removeSavedNow(app, requireNotNull(SavedRef.download(SavedShelf.Card, "absent", "absent"))))

        awaitSettled(phone.manager)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Admitted once the manager is quiet", card.cache.isCached(kept, 0, payload.size.toLong()))
    }

    @Test fun aQueuedRemovalBeforeAMoveIsNotMistakenForAQuietManager() {
        val service = service()
        awaitSettled(phone.manager)
        service.get().onStartCommand(removal(), 0, 1)
        assertFalse(phone.manager.isIdle)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("No move reads a source with a pending removal", card.cache.getCachedSpans(kept).isEmpty())
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload(kept))
        assertTrue(phone.cache.getCachedSpans(kept).isEmpty())
    }

    @Test fun anUnacknowledgedHandoverRefusesAnotherMoveEvenWithIdleManagers() {
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle() // Sends Add; this fixture does not deliver captured service starts.
        val store = requireNotNull(OfflineStore.current())
        assertTrue(store.moves.hasPending)
        assertTrue(phone.manager.isIdle)
        assertTrue(card.manager.isIdle)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Saved copies are still being saved, removed or moved, so nothing was moved. " +
            "Try again when that has finished.", ShadowToast.getTextOfLatestToast())
        assertTrue("Pending moves keep their original row", phone.manager.downloadIndex.getDownload(kept) != null)
        // An explicit user removal invalidates the hand-over rather than leaving a permanent busy marker.
        assertEquals(OfflineStore.SavedRemoval.Sent, OfflineStore.removeSavedNow(app,
            requireNotNull(SavedRef.download(SavedShelf.Phone, kept, kept))))
        assertFalse(store.moves.hasPending)
    }

    @Test fun aSecondMoveIsRefusedWhileTheFirstIsInFlight() {
        val hold = holdMover()
        OfflineStore.move(app, toCard = true)
        OfflineStore.move(app, toCard = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Saved copies are still being saved, removed or moved, so nothing was moved. " +
            "Try again when that has finished.", ShadowToast.getTextOfLatestToast())
        hold.countDown()
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(card.cache.isCached(kept, 0, payload.size.toLong()))
        // After the first finishes, the exclusion is free again: a saved-copy removal is no longer refused.
        assertNotEquals(OfflineStore.SavedRemoval.Busy,
            OfflineStore.removeSavedNow(app, requireNotNull(SavedRef.download(SavedShelf.Phone, kept, kept))))
    }

    @Test fun queuedWorkInAPausedManagerStillRefusesAMoveThoughTheManagerReportsIdle() {
        // A paused manager (no service has resumed it) holding a queued download: Media3 calls it idle.
        val index = DefaultDownloadIndex(database, "queued_card")
        index.putDownload(Download(request("queued"), Download.STATE_QUEUED, 0L, 0L, payload.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        val queuedCard = shelf("queued_card", MuonCardDownloadService::class.java, index)
        (OfflineStore.current() ?: error("fixture store")).card = queuedCard
        awaitSettled(queuedCard.manager)
        assertTrue(queuedCard.manager.isIdle)
        assertTrue(queuedCard.manager.downloadsPaused)
        OfflineStore.move(app, toCard = true)
        awaitMover()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Saved copies are still being saved, removed or moved, so nothing was moved. " +
            "Try again when that has finished.", ShadowToast.getTextOfLatestToast())
        assertTrue(queuedCard.cache.getCachedSpans(kept).isEmpty())
        assertEquals(Download.STATE_QUEUED, index.getDownload("queued")?.state)
    }

    // ---- #253: bulk commands that would reach only the manager's in-memory rows never reach Media3 ----

    private val unsupported = "Muon doesn't support that download command, so nothing was changed."

    @Test fun removeAllAndStopReasonCommandsAreRefusedBeforeMedia3EvenWithAMoveToken() {
        val service = service()
        awaitSettled(phone.manager)
        val clazz = MuonDownloadService::class.java
        listOf(
            DownloadService.buildRemoveAllDownloadsIntent(app, clazz, false),
            DownloadService.buildSetStopReasonIntent(app, clazz, null, 7, false),
            DownloadService.buildSetStopReasonIntent(app, clazz, kept, 7, false),
            // A process move token never lets one through, and no receipt is touched by it.
            DownloadService.buildRemoveAllDownloadsIntent(app, clazz, false).putExtra(OfflineStore.MOVE_COMMAND_TOKEN, "any"),
            DownloadService.buildSetStopReasonIntent(app, clazz, kept, 7, false).putExtra(OfflineStore.MOVE_COMMAND_TOKEN, "any"),
        ).forEachIndexed { startId, command ->
            service.get().onStartCommand(command, 0, startId + 1)
            awaitSettled(phone.manager)
            assertEquals(unsupported, ShadowToast.getTextOfLatestToast())
        }
        // Delivered, remove-all would have deleted this completed row and its bytes, and either stop reason
        // command would have set its stop reason (Media3 applies both to completed rows in the index).
        val row = requireNotNull(phone.manager.downloadIndex.getDownload(kept))
        assertEquals(Download.STATE_COMPLETED, row.state)
        assertEquals(Download.STOP_REASON_NONE, row.stopReason)
        assertTrue(phone.cache.isCached(kept, 0, payload.size.toLong()))
        assertFalse(requireNotNull(OfflineStore.current()).moves.hasPending)
    }

    @Test fun theCardServiceRefusesThemForItsOwnManagerToo() {
        complete(card, "saved/on-card")
        val cardService = Robolectric.buildService(MuonCardDownloadService::class.java).create()
        try {
            awaitSettled(card.manager)
            val clazz = MuonCardDownloadService::class.java
            cardService.get().onStartCommand(DownloadService.buildRemoveAllDownloadsIntent(app, clazz, false), 0, 1)
            cardService.get().onStartCommand(DownloadService.buildSetStopReasonIntent(app, clazz, null, 7, false), 0, 2)
            awaitSettled(card.manager)
            assertEquals(unsupported, ShadowToast.getTextOfLatestToast())
            val row = requireNotNull(card.manager.downloadIndex.getDownload("saved/on-card"))
            assertEquals(Download.STATE_COMPLETED, row.state)
            assertEquals(Download.STOP_REASON_NONE, row.stopReason)
            assertTrue(card.cache.isCached("saved/on-card", 0, payload.size.toLong()))
        } finally { cardService.destroy() }
    }

    @Test fun userRemoveAllStillRemovesSoleOwnersThroughCheckedPerRowCommands() {
        val service = service()
        awaitSettled(phone.manager)
        startedServices() // Ignore setup starts.
        assertEquals("One sole-owned row sent, none kept", 1 to 0, OfflineStore.removeAllNow(app))
        val commands = startedServices().filter { it.component?.className == MuonDownloadService::class.java.name }
        assertEquals(listOf(kept), commands.map {
            assertEquals(DownloadService.ACTION_REMOVE_DOWNLOAD, it.action)
            it.getStringExtra(DownloadService.KEY_CONTENT_ID)
        })
        commands.forEachIndexed { startId, command -> service.get().onStartCommand(command, 0, startId + 1) }
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload(kept))
        assertTrue(phone.cache.getCachedSpans(kept).isEmpty())
    }

    @Test fun aRealServiceProtectsAndThenRemovesAnOmittedRetainedRow() {
        val native = DefaultDownloadIndex(database, "omitted_service")
        val raw = DownloadRequest.Builder("saved/omitted", request("saved/omitted").uri)
            .setCustomCacheKey("saved/omitted").setData(ByteArray(32 * 1024) { 7 }).build()
        val original = Download(raw, Download.STATE_STOPPED, 10, 20, payload.size.toLong(),
            RETAINED_STOP_REASON, Download.FAILURE_REASON_NONE)
        native.putDownload(original)
        phone = shelf("omitted_service", MuonDownloadService::class.java, ManagerStartupIndex(RetainedDownloadIndex(native, folders.newFile())))
        val hole = requireNotNull(phone.cache.startReadWrite(raw.id, 0, payload.size.toLong()))
        try {
            val file = phone.cache.startFile(raw.id, 0, payload.size.toLong())
            file.writeBytes(payload); phone.cache.commitFile(file, payload.size.toLong())
        } finally { phone.cache.releaseHoleSpan(hole) }
        val fixture = OfflineStore.Store(phone, DownloadArt(folders.newFolder("omitted_art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, app.getSharedPreferences("omitted_service", Context.MODE_PRIVATE),
            database, {}, {})
        storeField.set(null, fixture)
        awaitSettled(phone.manager)
        assertTrue(phone.manager.currentDownloads.isEmpty())
        val service = service()
        val clazz = MuonDownloadService::class.java
        listOf(DownloadService.buildRemoveAllDownloadsIntent(app, clazz, false),
            DownloadService.buildSetStopReasonIntent(app, clazz, raw.id, Download.STOP_REASON_NONE, false),
            DownloadService.buildSetStopReasonIntent(app, clazz, null, Download.STOP_REASON_NONE, false))
            .forEachIndexed { startId, command -> service.get().onStartCommand(command, 0, startId + 1) }
        awaitSettled(phone.manager)
        assertEquals(original.request, native.getDownload(raw.id)?.request)
        assertEquals(Download.STATE_STOPPED, native.getDownload(raw.id)?.state)
        assertEquals(RETAINED_STOP_REASON, native.getDownload(raw.id)?.stopReason)
        assertTrue(phone.manager.currentDownloads.isEmpty())
        assertTrue(phone.cache.isCached(raw.id, 0, payload.size.toLong()))
        service.get().onStartCommand(DownloadService.buildRemoveDownloadIntent(app, clazz, raw.id, false), 0, 4)
        awaitSettled(phone.manager)
        assertNull(native.getDownload(raw.id))
        assertTrue(phone.cache.getCachedSpans(raw.id).isEmpty())
    }

    @Test fun rapidAddsAreBoundedBeforeCallbacksAndStoppedRowsStillCountAfterIdle() {
        phone.commands = DownloadCommandBudget(capacity = 2)
        val service = service()
        awaitSettled(phone.manager)
        fun add(id: String, start: Int) = service.get().onStartCommand(
            DownloadService.buildAddDownloadIntent(app, MuonDownloadService::class.java, request(id), 7, false), 0, start)
        add("first", 1)
        add("second", 2)
        add("third", 3) // No main callbacks pumped between deliveries: currentDownloads is stale.
        awaitSettled(phone.manager)
        assertEquals(setOf("first", "second"), phone.manager.currentDownloads.map { it.request.id }.toSet())
        assertTrue(phone.manager.currentDownloads.all { it.state == Download.STATE_STOPPED })
        assertNull(phone.manager.downloadIndex.getDownload("third"))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("wasn't queued"))
        add("third", 4) // Idle is not empty: STOPPED requests still occupy the budget.
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload("third"))

        // A full budget must not trap its own residents: removal is allowed, then room is reclaimed.
        service.get().onStartCommand(DownloadService.buildRemoveDownloadIntent(app,
            MuonDownloadService::class.java, "first", false), 0, 5)
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload("first"))
        add("third", 6)
        awaitSettled(phone.manager)
        assertEquals(setOf("second", "third"), phone.manager.currentDownloads.map { it.request.id }.toSet())
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(kept)?.state)
        assertTrue(phone.cache.isCached(kept, 0, payload.size.toLong()))
    }

    @Test fun removalRehydrationOverBudgetKeepsTheExactStoredRequestAndBytesThenRetryWorks() {
        val raw = request(kept, ByteArray(32 * 1024) { 91 })
        val original = Download(raw, Download.STATE_COMPLETED, 19, 23, payload.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        (phone.manager.downloadIndex as DefaultDownloadIndex).putDownload(original)
        phone.commands = DownloadCommandBudget(maximum = moveRequestBytes(raw) - 1)
        val service = service()
        awaitSettled(phone.manager)
        service.get().onStartCommand(removal(), 0, 1)
        awaitSettled(phone.manager)
        val keptRow = requireNotNull(phone.manager.downloadIndex.getDownload(kept))
        assertEquals(raw, keptRow.request)
        assertEquals(original.startTimeMs, keptRow.startTimeMs)
        assertEquals(original.updateTimeMs, keptRow.updateTimeMs)
        assertEquals(Download.STATE_COMPLETED, keptRow.state)
        assertTrue(phone.cache.isCached(kept, 0, payload.size.toLong()))
        phone.commands = DownloadCommandBudget(maximum = moveRequestBytes(raw))
        service.get().onStartCommand(removal(), 0, 2)
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload(kept))
        assertTrue(phone.cache.getCachedSpans(kept).isEmpty())
    }

    @Test fun addAccountsForOldRawDataEvenWhenTheIncomingRequestIsSmall() {
        val raw = request(kept, ByteArray(32 * 1024) { 17 })
        (phone.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(raw, Download.STATE_COMPLETED,
            1, 2, payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        val small = request(kept)
        phone.commands = DownloadCommandBudget(maximum = moveRequestBytes(raw) + moveRequestBytes(small) - 1)
        val service = service()
        awaitSettled(phone.manager)
        service.get().onStartCommand(DownloadService.buildAddDownloadIntent(app,
            MuonDownloadService::class.java, small, 7, false), 0, 1)
        awaitSettled(phone.manager)
        assertEquals(raw, phone.manager.downloadIndex.getDownload(kept)?.request)
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(kept)?.state)
        assertTrue(phone.manager.currentDownloads.isEmpty())
        assertTrue(phone.cache.isCached(kept, 0, payload.size.toLong()))
    }

    @Test fun repeatedSameIdAddsAreChargedAsCommandsBeforeTheManagerAcknowledgesThem() {
        phone.commands = DownloadCommandBudget(capacity = 2)
        val service = service()
        awaitSettled(phone.manager)
        val requests = (1..3).map { request("repeat", byteArrayOf(it.toByte())) }
        requests.forEachIndexed { i, request ->
            service.get().onStartCommand(DownloadService.buildAddDownloadIntent(app,
                MuonDownloadService::class.java, request, 7, false), 0, i + 1)
        }
        awaitSettled(phone.manager)
        assertArrayEquals(byteArrayOf(2), phone.manager.downloadIndex.getDownload("repeat")?.request?.data)
        assertEquals(1, phone.manager.currentDownloads.size)
        // Once settled, the retained row consumes one slot and a normal update is possible again.
        service.get().onStartCommand(DownloadService.buildAddDownloadIntent(app,
            MuonDownloadService::class.java, requests.last(), 7, false), 0, 4)
        awaitSettled(phone.manager)
        assertArrayEquals(byteArrayOf(3), phone.manager.downloadIndex.getDownload("repeat")?.request?.data)
    }

    @Test fun removalTasksHaveTheirOwnCapAndExcessRowsRemainAvailableForRetry() {
        val ids = (1..5).map { "remove-$it" }
        ids.forEach { complete(phone, it) }
        val service = service()
        awaitSettled(phone.manager)
        val hold = CountDownLatch(1).also(holds::add)
        removalHold = hold
        ids.forEachIndexed { i, id ->
            service.get().onStartCommand(DownloadService.buildRemoveDownloadIntent(app,
                MuonDownloadService::class.java, id, false), 0, i + 1)
        }
        shadowOf(Looper.getMainLooper()).idle() // Publish the refusal without waiting for held tasks.
        assertTrue(ShadowToast.getTextOfLatestToast().contains("wasn't queued"))
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(ids.last())?.state)
        assertTrue(phone.cache.isCached(ids.last(), 0, payload.size.toLong()))
        hold.countDown()
        awaitSettled(phone.manager)
        ids.dropLast(1).forEach { assertNull(phone.manager.downloadIndex.getDownload(it)) }
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(ids.last())?.state)
        service.get().onStartCommand(DownloadService.buildRemoveDownloadIntent(app,
            MuonDownloadService::class.java, ids.last(), false), 0, 6)
        awaitSettled(phone.manager)
        assertNull(phone.manager.downloadIndex.getDownload(ids.last()))
        assertTrue(phone.cache.getCachedSpans(ids.last()).isEmpty())
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(kept)?.state)
    }

    @Test fun aBudgetRefusedMoveTokenReleasesTheReceiptWithoutRemovingEitherCopy() {
        complete(card, kept)
        val store = requireNotNull(OfflineStore.current())
        assertTrue(store.moves.remember(card, phone, request(kept)))
        val receipt = requireNotNull(store.moves.find(phone, kept))
        phone.commands = DownloadCommandBudget(capacity = 0)
        val service = service()
        awaitSettled(phone.manager)
        service.get().onStartCommand(DownloadService.buildAddDownloadIntent(app,
            MuonDownloadService::class.java, receipt.request, false)
            .putExtra(OfflineStore.MOVE_COMMAND_TOKEN, receipt.token), 0, 1)
        awaitSettled(phone.manager)
        assertFalse(store.moves.hasPending)
        assertEquals(Download.STATE_COMPLETED, phone.manager.downloadIndex.getDownload(kept)?.state)
        assertEquals(Download.STATE_COMPLETED, card.manager.downloadIndex.getDownload(kept)?.state)
        assertTrue(phone.cache.isCached(kept, 0, payload.size.toLong()))
        assertTrue(card.cache.isCached(kept, 0, payload.size.toLong()))
    }

    private fun startedServices(): List<android.content.Intent> = buildList {
        val shadow = shadowOf(app)
        while (true) add(shadow.nextStartedService ?: break)
    }

    // ---- fixture ----

    private fun removal() = DownloadService.buildRemoveDownloadIntent(app, MuonDownloadService::class.java, kept, false)

    private fun service(): ServiceController<MuonDownloadService> =
        Robolectric.buildService(MuonDownloadService::class.java).create().also(services::add)

    /** Queues a task on the real mover ahead of anything else, and waits until it is running. */
    private fun holdMover(): CountDownLatch {
        val release = CountDownLatch(1).also(holds::add)
        val running = CountDownLatch(1)
        (moverField.get(OfflineStore) as ExecutorService).execute {
            running.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun awaitMover() {
        (moverField.get(OfflineStore) as ExecutorService).submit {}.get(10, TimeUnit.SECONDS)
    }

    /** Bounded: pumps the main looper until the manager has loaded its index and processed every command. */
    private fun awaitSettled(manager: DownloadManager) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (manager.isInitialized && manager.isIdle) return
            check(System.nanoTime() < deadline) { "The manager did not settle" }
            Thread.sleep(5) // Yields to Media3's real internal handler thread; not a timing assertion.
        }
    }

    private fun shelf(name: String, service: Class<out DownloadService>,
        index: androidx.media3.exoplayer.offline.WritableDownloadIndex = DefaultDownloadIndex(database, name)): Shelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        // Removal deletes the row's real bytes; downloading is never allowed.
        val downloaders = DownloaderFactory { request ->
            object : Downloader {
                override fun download(progressListener: Downloader.ProgressListener?) = error("No downloading in this fixture")
                override fun cancel() = Unit
                override fun remove() {
                    removalHold?.let { check(it.await(10, TimeUnit.SECONDS)) }
                    cache.removeResource(request.customCacheKey ?: request.id)
                }
            }
        }
        return Shelf(cache, DownloadManager(app, index, downloaders), service).also(shelves::add)
    }

    private fun request(id: String, data: ByteArray = byteArrayOf()): DownloadRequest =
        DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/7")).setCustomCacheKey(id).setData(data).build()

    private fun complete(shelf: Shelf, id: String) {
        val hole = requireNotNull(shelf.cache.startReadWrite(id, 0, payload.size.toLong()))
        try {
            val file = shelf.cache.startFile(id, 0, payload.size.toLong())
            file.writeBytes(payload)
            shelf.cache.commitFile(file, payload.size.toLong())
        } finally { shelf.cache.releaseHoleSpan(hole) }
        shelf.cache.applyContentMetadataMutations(id,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), payload.size.toLong()))
        (shelf.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(request(id), Download.STATE_COMPLETED,
            0L, 0L, payload.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }
}
