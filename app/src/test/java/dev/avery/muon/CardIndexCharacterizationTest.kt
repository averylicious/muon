package dev.avery.muon

import android.content.Context
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
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

/** #179: distinct card folders with the production shared index name; no Android mount events. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CardIndexCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: Shelf
    private val shelves = mutableListOf<Shelf>()
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previousStore: Any? = null
    private val endpoint = ServerEndpoint.parse("http://192.168.1.20:7814")
    private val track = TauonTrack(7, "Card A title", "Artist", "Album", 180_000, true, false)
    private val id get() = downloadId(endpoint.origin, track.id)
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        phone = shelf(folders.newFolder("phone"), "", MuonDownloadService::class.java)
        previousStore = storeField.get(null)
    }

    @After fun tearDown() {
        try {
            storeField.set(null, previousStore)
            shelves.asReversed().forEach { it.manager.release(); it.cache.release() }
        } finally { database.close() }
    }

    @Test fun sameMountedCardReopenRetainsCompletionAndExactBytes() {
        val folder = folders.newFolder("card_a")
        val a = shelf(folder, "card")
        complete(a)
        val uid = a.cache.uid
        close(a)
        val reopened = shelf(folder, "card")
        attach(reopened)
        assertEquals(uid, reopened.cache.uid)
        assertTrue(OfflineStore.downloaded(RuntimeEnvironment.getApplication(), id))
        assertArrayEquals(bytes, requireNotNull(reopened.cache.getCachedSpans(id).first().file).readBytes())
        assertEquals(listOf(track), OfflineStore.downloadedSongs(RuntimeEnvironment.getApplication(), endpoint.origin))
    }

    @Test fun differentEmptyCardInheritsCompletionAndOfflineMetadataFromSharedIndex() {
        val a = shelf(folders.newFolder("card_a"), "card")
        complete(a)
        close(a) // Mounted orderly release: this fixture does not trigger the separate index-loss bug.
        val b = shelf(folders.newFolder("card_b"), "card")
        attach(b)
        assertTrue(b.completed(id))
        assertFalse(b.cache.isCached(id, 0, bytes.size.toLong()))
        assertSame(b, OfflineStore.downloadedOn(RuntimeEnvironment.getApplication(), id))
        assertEquals(listOf(track), OfflineStore.downloadedSongs(RuntimeEnvironment.getApplication(), endpoint.origin))
        val spec = DataSpec.Builder().setUri(Uri.parse(endpoint.url("/api1/file/7"))).build()
        val route = routeOfflineRequest(spec, phone, listOf(phone, b), offline = true)
        assertSame(b, route.first)
        assertEquals(id, route.second.key)
        assertEquals(endpoint.url("/api1/fileopus/7"), route.second.uri.toString())
        assertFalse("Phone's default index is independent", phone.completed(id))
    }

    @Test fun distinctIndexControlDoesNotInheritAnotherCardsCompletedRecord() {
        val aFolder = folders.newFolder("card_a")
        val a = shelf(aFolder, "card")
        complete(a)
        close(a)
        val b = shelf(folders.newFolder("card_b"), "card_b_fixture")
        attach(b)
        assertFalse(b.completed(id))
        assertNull(OfflineStore.downloadedOn(RuntimeEnvironment.getApplication(), id))
        assertTrue(OfflineStore.downloadedSongs(RuntimeEnvironment.getApplication(), endpoint.origin).isEmpty())
        val reopenedA = shelf(aFolder, "card")
        assertTrue(reopenedA.completed(id))
        assertArrayEquals(bytes, requireNotNull(reopenedA.cache.getCachedSpans(id).first().file).readBytes())
        // The fixture name is only a control, not a production volume ID/migration proposal.
    }

    private fun attach(card: Shelf) {
        val app = RuntimeEnvironment.getApplication()
        val store = OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, app.getSharedPreferences("card-fixture", Context.MODE_PRIVATE),
            database, {}, {})
        store.card = card
        storeField.set(null, store)
    }

    private fun shelf(folder: File, name: String,
        service: Class<out androidx.media3.exoplayer.offline.DownloadService> = MuonCardDownloadService::class.java): Shelf {
        val cache = SimpleCache(folder, NoOpCacheEvictor(), database)
        cache.checkInitialization()
        return Shelf(cache, DownloadManager(RuntimeEnvironment.getApplication(), DefaultDownloadIndex(database, name),
            DownloaderFactory { error("Fixture must not start a downloader/network") }), service).also(shelves::add)
    }

    private fun complete(shelf: Shelf) {
        val hole = shelf.cache.startReadWrite(id, 0, bytes.size.toLong())
        try {
            val file = shelf.cache.startFile(id, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            shelf.cache.commitFile(file, bytes.size.toLong())
            shelf.cache.applyContentMetadataMutations(id,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        } finally { shelf.cache.releaseHoleSpan(hole) }
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/7")))
            .setCustomCacheKey(id).setData(encodeSong(track)).build()
        (shelf.manager.downloadIndex as DefaultDownloadIndex).putDownload(Download(request, Download.STATE_COMPLETED,
            1, 1, bytes.size.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun close(shelf: Shelf) {
        shelf.manager.release(); shelf.cache.release(); shelves.remove(shelf)
    }
}
