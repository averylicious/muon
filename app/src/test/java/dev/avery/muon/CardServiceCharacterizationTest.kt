package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import java.util.concurrent.TimeUnit
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

/** Actual service/helper lifetime and manually delivered command intents; no download I/O, network or mount events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CardServiceCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var store: OfflineStore.Store
    private val shelves = mutableListOf<Shelf>()
    private val services = mutableListOf<ServiceController<MuonCardDownloadService>>()
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previousStore: Any? = null

    @Before fun setUp() {
        // Only this disposable test owns services; no production process or other live service.
        DownloadService.clearDownloadManagerHelpers()
        val app = RuntimeEnvironment.getApplication()
        database = StandaloneDatabaseProvider(app)
        val phone = shelf("phone", "", MuonDownloadService::class.java)
        store = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")), PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {},
            app.getSharedPreferences("card-service-fixture", Context.MODE_PRIVATE), database, {}, {})
        previousStore = storeField.get(null)
        storeField.set(null, store)
    }

    @After fun tearDown() {
        try {
            services.asReversed().forEach { it.destroy() }
            services.clear()
            storeField.set(null, previousStore)
            shelves.asReversed().forEach { it.manager.release(); it.cache.release() }
            DownloadService.clearDownloadManagerHelpers()
        } finally { database.close() }
    }

    @Test fun missingCardInitiallySelectsPhoneAndLateInsertionDoesNotChangeHelper() {
        val first = service()
        assertSame(store.phone.manager, selected(first.get()))
        first.destroy(); services.remove(first)
        val card = shelf("late_card", "late_card_fixture")
        store.card = card
        val restarted = service()
        assertSame("Class helper is retained despite a now-available card", store.phone.manager, selected(restarted.get()))
        assertNotSame(card.manager, selected(restarted.get()))
    }

    @Test fun restartAfterChangingCardShelfStillUsesOriginalCardManager() {
        val original = shelf("original_card", "original_card_fixture")
        store.card = original
        val first = service()
        assertSame(original.manager, selected(first.get()))
        first.destroy(); services.remove(first)
        val replacement = shelf("replacement_card", "replacement_card_fixture")
        store.card = replacement
        val restarted = service()
        assertSame(original.manager, selected(restarted.get()))
        assertNotSame(replacement.manager, selected(restarted.get()))
        assertNotSame(store.phone.manager, selected(restarted.get()))
    }

    @Test fun clearingHelpersDoesNotRebindALiveService() {
        val original = shelf("live_original", "live_original_fixture")
        store.card = original
        val live = service()
        assertSame(original.manager, selected(live.get()))
        val replacement = shelf("live_replacement", "live_replacement_fixture")
        store.card = replacement

        // Deliberately characterize the unsafe shortcut with idle disposable managers only.
        // Map clearing is not revocation of the helper already held by the live service.
        DownloadService.clearDownloadManagerHelpers()
        assertSame(original.manager, selected(live.get()))
        assertNotSame(replacement.manager, selected(live.get()))
    }

    @Test fun destroyedServiceWithClearedHelpersSelectsReplacementManager() {
        val original = shelf("reset_original", "reset_original_fixture")
        store.card = original
        val first = service()
        assertSame(original.manager, selected(first.get()))
        first.destroy(); services.remove(first)
        val replacement = shelf("reset_replacement", "reset_replacement_fixture")
        store.card = replacement

        // Positive lookup-reset control for the existing no-reset/reuse test. No tasks are admitted;
        // this does not establish manager release, worker/read drain or card-generation safety.
        DownloadService.clearDownloadManagerHelpers()
        val restarted = service()
        assertSame(replacement.manager, selected(restarted.get()))
        assertNotSame(original.manager, selected(restarted.get()))
        assertNotSame(store.phone.manager, selected(restarted.get()))
    }

    // Synthetic callbacks: the real retained helper is called directly with a constructed Download. No
    // task, download, network or card runs, and no foreground execution is claimed.

    @Test fun destroyedAndClearedHelperStaysOnItsManagerAndCanRequestARestart() {
        val original = shelf("retained_original", "retained_original_fixture")
        store.card = original
        val first = service()
        val helper = helper(first.get())
        assertTrue(listeners(original.manager).contains(helper))
        first.destroy(); services.remove(first)
        DownloadService.clearDownloadManagerHelpers()
        assertTrue("Detach and map clearing leave the helper registered", listeners(original.manager).contains(helper))

        drainStartedServices()
        helper.onDownloadChanged(original.manager, download(Download.STATE_COMPLETED), null)
        assertNull("A terminal state requests no restart", startedService())
        helper.onDownloadChanged(original.manager, download(Download.STATE_DOWNLOADING), null)
        val restart = requireNotNull(startedService()) { "An active state requests a restart" }
        assertEquals(MuonCardDownloadService::class.java.name, restart.component?.className)
        assertEquals(RESTART, restart.action)
        assertNull(startedService())
    }

    @Test fun anOldHelperStillRestartsTheClassAfterAReplacementServiceBinds() {
        val original = shelf("old_helper_original", "old_helper_original_fixture")
        store.card = original
        val first = service()
        val old = helper(first.get())
        first.destroy(); services.remove(first)
        DownloadService.clearDownloadManagerHelpers()
        val replacement = shelf("old_helper_replacement", "old_helper_replacement_fixture")
        store.card = replacement
        val fresh = service()
        assertSame(replacement.manager, selected(fresh.get()))
        assertNotSame(old, helper(fresh.get()))
        assertTrue("The old helper is still on the old manager", listeners(original.manager).contains(old))
        assertFalse(listeners(replacement.manager).contains(old))

        drainStartedServices()
        old.onDownloadChanged(original.manager, download(Download.STATE_DOWNLOADING), null)
        // The class it restarts is the one now bound to the replacement manager.
        val restart = requireNotNull(startedService()) { "The old callback still requests a restart" }
        assertEquals(MuonCardDownloadService::class.java.name, restart.component?.className)
        assertEquals(RESTART, restart.action)
        assertNull(startedService())
    }

    // These pause/resume intents carry service/action/foreground state but no shelf generation; the current helper selects the manager.
    // Delivery here is manual (the real onStartCommand), not Android's queue scheduling. No tasks or network.

    @Test fun aCommandQueuedForTheOldCardRunsAgainstTheReplacementManager() {
        val app = RuntimeEnvironment.getApplication()
        val original = shelf("queued_original", "queued_original_fixture")
        store.card = original
        val first = service() // onCreate resumes the original manager
        drainStartedServices()
        original.manager.pauseDownloads()
        DownloadService.sendResumeDownloads(app, MuonCardDownloadService::class.java, false)
        val queued = requireNotNull(startedService()) { "The resume command is queued" }
        assertEquals(DownloadService.ACTION_RESUME_DOWNLOADS, queued.action)
        assertEquals(MuonCardDownloadService::class.java.name, queued.component?.className)
        assertFalse(queued.getBooleanExtra(DownloadService.KEY_FOREGROUND, false))
        assertTrue("Queued only: nothing delivered it to the original", original.manager.downloadsPaused)

        first.destroy(); services.remove(first)
        DownloadService.clearDownloadManagerHelpers()
        val replacement = shelf("queued_replacement", "queued_replacement_fixture")
        store.card = replacement
        val fresh = service() // onCreate resumes the replacement, so pause it to see the command
        assertSame(replacement.manager, selected(fresh.get()))
        replacement.manager.pauseDownloads()

        fresh.get().onStartCommand(queued, 0, 1)
        assertFalse("The old card's command resumed the replacement manager", replacement.manager.downloadsPaused)
        assertTrue("The original manager is untouched", original.manager.downloadsPaused)
        assertNull(startedService())
    }

    @Test fun aCardCommandDeliveredWithNoCardSelectedLeavesThePhoneManagerAlone() {
        val app = RuntimeEnvironment.getApplication()
        val card = shelf("fallback_card", "fallback_card_fixture")
        store.card = card
        val first = service()
        drainStartedServices()
        DownloadService.sendPauseDownloads(app, MuonCardDownloadService::class.java, false)
        val queued = requireNotNull(startedService()) { "The pause command is queued" }
        assertEquals(DownloadService.ACTION_PAUSE_DOWNLOADS, queued.action)
        assertEquals(MuonCardDownloadService::class.java.name, queued.component?.className)
        assertFalse(queued.getBooleanExtra(DownloadService.KEY_FOREGROUND, false))
        assertFalse("Queued only: the card manager is still resumed", card.manager.downloadsPaused)

        first.destroy(); services.remove(first)
        DownloadService.clearDownloadManagerHelpers()
        store.card = null
        val fresh = service() // The card service's existing fallback selects (and resumes) the phone manager
        assertSame(store.phone.manager, selected(fresh.get()))
        assertFalse(store.phone.manager.downloadsPaused)

        fresh.get().onStartCommand(queued, 0, 1)
        assertFalse("A card-class command is not carried out on the phone manager", store.phone.manager.downloadsPaused)
        assertFalse("The card manager is untouched", card.manager.downloadsPaused)
        assertEquals("The delivered intent itself is not rewritten", DownloadService.ACTION_PAUSE_DOWNLOADS, queued.action)
        assertNull(startedService())
    }

    // #179 command admission: changing commands reach only the card manager this instance's helper was built
    // with, and only while that is the store's card. Delivery is still the real onStartCommand called directly.

    @Test fun noChangingCardCommandAltersAFallbackPhoneManager() {
        val app = RuntimeEnvironment.getApplication()
        val phone = seededPhone()
        val fallback = service() // No card: Media3 builds the helper with the phone manager and resumes it
        assertSame(phone.manager, selected(fallback.get()))
        awaitSettled(phone.manager)
        assertFalse(phone.manager.downloadsPaused)

        val card = MuonCardDownloadService::class.java
        listOf(
            DownloadService.buildAddDownloadIntent(app, card, request("added"), false),
            DownloadService.buildRemoveDownloadIntent(app, card, KEPT, false),
            DownloadService.buildSetStopReasonIntent(app, card, KEPT, Download.STOP_REASON_NONE, false),
            DownloadService.buildRemoveAllDownloadsIntent(app, card, false),
            DownloadService.buildSetRequirementsIntent(app, card, Requirements(Requirements.NETWORK_UNMETERED), false),
            DownloadService.buildPauseDownloadsIntent(app, card, false),
        ).forEachIndexed { startId, command -> fallback.get().onStartCommand(command, 0, startId + 1) }
        assertFalse("Pause refused", phone.manager.downloadsPaused)
        phone.manager.pauseDownloads() // Directly, so that a delivered resume would show
        fallback.get().onStartCommand(DownloadService.buildResumeDownloadsIntent(app, card, false), 0, 7)
        assertTrue("Resume refused", phone.manager.downloadsPaused)

        // Anything delivered would have been queued on the manager before this settles.
        awaitSettled(phone.manager)
        assertEquals(DownloadManager.DEFAULT_REQUIREMENTS, phone.manager.requirements)
        assertNull("Add refused", phone.manager.downloadIndex.getDownload("added"))
        val kept = requireNotNull(phone.manager.downloadIndex.getDownload(KEPT)) { "Remove and remove-all refused" }
        assertEquals(Download.STATE_STOPPED, kept.state)
        assertEquals("Stop reason unchanged", KEPT_REASON, kept.stopReason)
        assertEquals(listOf(KEPT), phone.manager.currentDownloads.map { it.request.id })
    }

    @Test fun cardCommandsReachTheCardManagerFromTheFirstAndARecreatedInstance() {
        val app = RuntimeEnvironment.getApplication()
        val card = shelf("bound_card", "bound_card_fixture")
        store.card = card
        val first = service()
        awaitSettled(card.manager)
        val clazz = MuonCardDownloadService::class.java
        first.get().onStartCommand(DownloadService.buildAddDownloadIntent(app, clazz, request(KEPT), KEPT_REASON, false), 0, 1)
        first.get().onStartCommand(DownloadService.buildPauseDownloadsIntent(app, clazz, false), 0, 2)
        assertTrue(card.manager.downloadsPaused)
        awaitSettled(card.manager)
        assertEquals(KEPT_REASON, card.manager.downloadIndex.getDownload(KEPT)?.stopReason)

        first.destroy(); services.remove(first)
        val recreated = service() // Media3 reuses the helper and does not ask this instance for a manager
        assertSame(card.manager, selected(recreated.get()))
        recreated.get().onStartCommand(DownloadService.buildResumeDownloadsIntent(app, clazz, false), 0, 1)
        assertFalse("Admitted through the retained binding", card.manager.downloadsPaused)
        assertTrue("The phone manager, never resumed here, is untouched", store.phone.manager.downloadsPaused)
        assertNull(store.phone.manager.downloadIndex.getDownload(KEPT))
    }

    @Test fun aCardAddedAfterThePhoneFallbackDoesNotOpenTheRetainedHelper() {
        val app = RuntimeEnvironment.getApplication()
        val first = service() // No card yet: the class helper is built with the phone manager
        first.destroy(); services.remove(first)
        store.card = shelf("late_admission_card", "late_admission_card_fixture") // Artificial in-process assignment
        val recreated = service()
        assertSame("The helper is reused, still on the phone manager", store.phone.manager, selected(recreated.get()))
        recreated.get().onStartCommand(DownloadService.buildPauseDownloadsIntent(app, MuonCardDownloadService::class.java, false), 0, 1)
        assertFalse("A store card does not admit commands that would reach the phone", store.phone.manager.downloadsPaused)
    }

    @Test fun anOlderInstanceKeepsItsOwnBindingAfterATestOnlyClear() {
        val app = RuntimeEnvironment.getApplication()
        val original = shelf("receipt_original", "receipt_original_fixture")
        store.card = original
        val old = service()
        // Artificial: Robolectric lets a second instance exist while the first is live, which Android would not.
        DownloadService.clearDownloadManagerHelpers()
        val replacement = shelf("receipt_replacement", "receipt_replacement_fixture")
        store.card = replacement
        val fresh = service()
        assertSame(original.manager, selected(old.get()))
        assertSame(replacement.manager, selected(fresh.get()))

        val pause = DownloadService.buildPauseDownloadsIntent(app, MuonCardDownloadService::class.java, false)
        old.get().onStartCommand(pause, 0, 1)
        assertFalse("The old instance's helper reaches the original card, no longer the store's", original.manager.downloadsPaused)
        fresh.get().onStartCommand(pause, 0, 1)
        assertTrue(replacement.manager.downloadsPaused)
    }

    @Test fun commandAdmissionDoesNotCreateAnAbsentStore() {
        val app = RuntimeEnvironment.getApplication()
        val fallback = service()
        storeField.set(null, null) // Test-only disappearance, not a production retirement mechanism.
        val command = DownloadService.buildPauseDownloadsIntent(app, MuonCardDownloadService::class.java, false)
        fallback.get().onStartCommand(command, 0, 1)
        assertNull("Admission must not initialize another store", OfflineStore.current())
        assertFalse("The retained phone manager remains unchanged", store.phone.manager.downloadsPaused)
    }

    @Test fun aRefusedForegroundCommandStillShowsTheForegroundNotification() {
        val app = RuntimeEnvironment.getApplication()
        val fallback = service() // No card: bound to the phone manager
        val command = DownloadService.buildPauseDownloadsIntent(app, MuonCardDownloadService::class.java, true)
        fallback.get().onStartCommand(command, 0, 1)
        assertFalse(store.phone.manager.downloadsPaused)
        assertNotNull("The foreground start's notification is shown", shadowOf(fallback.get()).lastForegroundNotification)
        assertEquals(CARD_NOTIFICATION_ID, shadowOf(fallback.get()).lastForegroundNotificationId)
        assertEquals(DownloadService.ACTION_PAUSE_DOWNLOADS, command.action)
        assertTrue(command.getBooleanExtra(DownloadService.KEY_FOREGROUND, false))
    }

    // #179 S1 at delivery: the bound card's availability is probed per command. The probe is a stand-in
    // lambda; no volume is mounted or removed, and no card generation is adopted on recovery.

    @Test fun everyChangingCommandIsRefusedWhileTheBoundCardIsUnavailable() {
        val app = RuntimeEnvironment.getApplication()
        DefaultDownloadIndex(database, "unavailable_card").putDownload(Download(request(KEPT), Download.STATE_STOPPED,
            0L, 0L, C.LENGTH_UNSET.toLong(), KEPT_REASON, Download.FAILURE_REASON_NONE))
        var available = true
        val card = shelf("unavailable_card", "unavailable_card", downloaders = inert, present = { available })
        store.card = card
        val bound = service() // Bound while available; Media3 resumes the card manager
        assertSame(card.manager, selected(bound.get()))
        awaitSettled(card.manager)
        assertFalse(card.manager.downloadsPaused)

        available = false
        val clazz = MuonCardDownloadService::class.java
        listOf(
            DownloadService.buildAddDownloadIntent(app, clazz, request("added"), false),
            DownloadService.buildRemoveDownloadIntent(app, clazz, KEPT, false),
            DownloadService.buildSetStopReasonIntent(app, clazz, KEPT, Download.STOP_REASON_NONE, false),
            DownloadService.buildRemoveAllDownloadsIntent(app, clazz, false),
            DownloadService.buildSetRequirementsIntent(app, clazz, Requirements(Requirements.NETWORK_UNMETERED), false),
            DownloadService.buildPauseDownloadsIntent(app, clazz, false),
        ).forEachIndexed { startId, command -> bound.get().onStartCommand(command, 0, startId + 1) }
        assertFalse("Pause refused", card.manager.downloadsPaused)
        card.manager.pauseDownloads() // Directly, so that a delivered resume would show
        bound.get().onStartCommand(DownloadService.buildResumeDownloadsIntent(app, clazz, false), 0, 7)
        assertTrue("Resume refused", card.manager.downloadsPaused)

        awaitSettled(card.manager)
        assertEquals(DownloadManager.DEFAULT_REQUIREMENTS, card.manager.requirements)
        assertNull("Add refused", card.manager.downloadIndex.getDownload("added"))
        val kept = requireNotNull(card.manager.downloadIndex.getDownload(KEPT)) { "Remove and remove-all refused" }
        assertEquals(Download.STATE_STOPPED, kept.state)
        assertEquals("Stop reason unchanged", KEPT_REASON, kept.stopReason)
        assertEquals(listOf(KEPT), card.manager.currentDownloads.map { it.request.id })
        assertTrue("The phone manager, never resumed here, is untouched", store.phone.manager.downloadsPaused)
        assertNull(store.phone.manager.downloadIndex.getDownload("added"))

        // The probe answering yes again admits the same binding's next command; nothing is re-created or adopted.
        available = true
        bound.get().onStartCommand(DownloadService.buildResumeDownloadsIntent(app, clazz, false), 0, 8)
        assertFalse(card.manager.downloadsPaused)
    }

    @Test fun aFailingAvailabilityProbeRefusesWithoutCrashing() {
        val app = RuntimeEnvironment.getApplication()
        val card = shelf("probe_card", "probe_card_fixture", present = { error("Fixture probe failure") })
        store.card = card
        val bound = service()
        assertSame(card.manager, selected(bound.get()))
        bound.get().onStartCommand(DownloadService.buildPauseDownloadsIntent(app, MuonCardDownloadService::class.java, false), 0, 1)
        assertFalse("A probe that throws counts as unavailable", card.manager.downloadsPaused)
    }

    @Test fun aRefusedForegroundCommandForAnUnavailableCardStillShowsItsNotification() {
        val app = RuntimeEnvironment.getApplication()
        var available = true
        val card = shelf("foreground_card", "foreground_card_fixture", present = { available })
        store.card = card
        val bound = service()
        available = false
        val command = DownloadService.buildPauseDownloadsIntent(app, MuonCardDownloadService::class.java, true)
        bound.get().onStartCommand(command, 0, 1)
        assertFalse(card.manager.downloadsPaused)
        assertNotNull("The foreground start's notification is shown", shadowOf(bound.get()).lastForegroundNotification)
        assertEquals(CARD_NOTIFICATION_ID, shadowOf(bound.get()).lastForegroundNotificationId)
        assertEquals(DownloadService.ACTION_PAUSE_DOWNLOADS, command.action)
        assertTrue(command.getBooleanExtra(DownloadService.KEY_FOREGROUND, false))
    }

    /** Replaces the fixture's phone with one whose index already holds a stopped download, and inert downloaders. */
    private fun seededPhone(): Shelf {
        DefaultDownloadIndex(database, "seeded_phone").putDownload(Download(request(KEPT), Download.STATE_STOPPED,
            0L, 0L, C.LENGTH_UNSET.toLong(), KEPT_REASON, Download.FAILURE_REASON_NONE))
        val phone = shelf("seeded_phone", "seeded_phone", MuonDownloadService::class.java, inert)
        store = OfflineStore.Store(phone, store.art, store.played, store.prefs, database, {}, {})
        storeField.set(null, store)
        awaitSettled(phone.manager)
        return phone
    }

    /** Waits until the manager has loaded its index and processed everything sent to it. */
    private fun awaitSettled(manager: DownloadManager) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (manager.isInitialized && manager.isIdle) return
            check(System.nanoTime() < deadline) { "The manager did not settle" }
            Thread.sleep(5)
        }
    }

    private fun request(id: String): DownloadRequest =
        DownloadRequest.Builder(id, Uri.parse("http://192.168.1.20:7814/api1/fileopus/$id")).build()

    /** Should a command wrongly get through, its task finishes at once without I/O. */
    private val inert = DownloaderFactory {
        object : Downloader {
            override fun download(progressListener: Downloader.ProgressListener?) = Unit
            override fun cancel() = Unit
            override fun remove() = Unit
        }
    }

    private fun helper(service: MuonCardDownloadService): DownloadManager.Listener =
        DownloadService::class.java.getDeclaredField("downloadManagerHelper")
            .apply { isAccessible = true }.get(service) as DownloadManager.Listener

    @Suppress("UNCHECKED_CAST")
    private fun listeners(manager: DownloadManager): Set<DownloadManager.Listener> =
        DownloadManager::class.java.getDeclaredField("listeners")
            .apply { isAccessible = true }.get(manager) as Set<DownloadManager.Listener>

    private fun download(state: Int): Download = Download(
        DownloadRequest.Builder("http://192.168.1.20:7814/7", Uri.parse("http://192.168.1.20:7814/api1/fileopus/7")).build(),
        state, 0L, 0L, C.LENGTH_UNSET.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)

    private fun startedService(): Intent? = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService

    private fun drainStartedServices() { while (startedService() != null) Unit }

    private fun service(): ServiceController<MuonCardDownloadService> =
        Robolectric.buildService(MuonCardDownloadService::class.java).create().also(services::add)

    private fun selected(service: MuonCardDownloadService): DownloadManager {
        val helper = DownloadService::class.java.getDeclaredField("downloadManagerHelper")
            .apply { isAccessible = true }.get(service)
        return helper.javaClass.getDeclaredField("downloadManager").apply { isAccessible = true }.get(helper) as DownloadManager
    }

    private fun shelf(folder: String, index: String,
        service: Class<out DownloadService> = MuonCardDownloadService::class.java,
        downloaders: DownloaderFactory = DownloaderFactory { error("Fixture must not start downloader/network") },
        present: () -> Boolean = { true }): Shelf {
        val cache = SimpleCache(folders.newFolder(folder), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val manager = DownloadManager(RuntimeEnvironment.getApplication(), DefaultDownloadIndex(database, index), downloaders)
        return Shelf(cache, manager, service, present).also(shelves::add)
    }

    private companion object {
        /** DownloadService.ACTION_RESTART is private; the pinned 1.11.0 value (DownloadService.java 71-72). */
        const val RESTART = "androidx.media3.exoplayer.downloadService.action.RESTART"
        /** MuonCardDownloadService's notification ID (private in MuonDownloadService.kt). */
        const val CARD_NOTIFICATION_ID = 3
        const val KEPT = "http://192.168.1.20:7814/kept"
        const val KEPT_REASON = 7
    }
}
