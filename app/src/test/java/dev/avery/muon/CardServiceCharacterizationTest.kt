package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.DownloadService
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

/** Actual DownloadService creation/helper reuse, without commands, network or Android mount events. */
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
        service: Class<out DownloadService> = MuonCardDownloadService::class.java): Shelf {
        val cache = SimpleCache(folders.newFolder(folder), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        val manager = DownloadManager(RuntimeEnvironment.getApplication(), DefaultDownloadIndex(database, index),
            DownloaderFactory { error("Fixture must not start downloader/network") })
        return Shelf(cache, manager, service).also(shelves::add)
    }

    private companion object {
        /** DownloadService.ACTION_RESTART is private; the pinned 1.11.0 value (DownloadService.java 71-72). */
        const val RESTART = "androidx.media3.exoplayer.downloadService.action.RESTART"
    }
}
