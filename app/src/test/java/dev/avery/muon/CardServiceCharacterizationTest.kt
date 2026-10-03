package dev.avery.muon

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
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
}
