package dev.avery.muon

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual LibraryModel state with disposable native index/cache and loopback API. No Compose/device test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedLibraryModelTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private lateinit var model: LibraryModel
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private var previous: Any? = null
    private var previousOffline = false
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("connection", Context.MODE_PRIVATE).edit().clear().commit()
        database = StandaloneDatabaseProvider(app)
        cache = SimpleCache(folders.newFolder("cache"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database, "saved_model")
        manager = DownloadManager(app, index, DownloaderFactory { error("This model fixture must never download audio") })
        pumpUntil { manager.isInitialized && manager.isIdle }
        val shelf = Shelf(cache, manager, MuonDownloadService::class.java)
        previous = storeField.get(null); previousOffline = OfflineStore.offline
        storeField.set(null, OfflineStore.Store(shelf, DownloadArt(folders.newFolder("art")),
            PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}, app.getSharedPreferences("fixture", 0), database, {}, {}))
        model = LibraryModel(app)
    }
    @After fun tearDown() {
        try {
            val job = model.viewModelScope.coroutineContext[Job]
            model.viewModelScope.cancel()
            pumpUntil { job?.isCompleted != false }
            storeField.set(null, previous); OfflineStore.offline = previousOffline
            manager.release(); cache.release()
        } finally { database.close() }
    }

    @Test fun unknownOriginAndTagsOpenOfflineAndSurviveDisconnectWithoutBecomingLiveTracks() {
        val request = unknownCopy()
        model.listenOffline()
        pumpUntil { !model.busy && model.offline }
        assertNull(model.endpoint)
        assertEquals("", model.address)
        assertTrue(model.allTracks.isEmpty())
        val first = model.saved.single()
        assertNull(first.song)
        assertTrue(first.complete)
        assertTrue(first.subtitle().contains(UNVERIFIED))
        val handle = first.ref.handle
        model.disconnect()
        assertFalse(model.offline)
        model.listenOffline()
        pumpUntil { !model.busy && model.offline }
        assertEquals(handle, model.saved.single().ref.handle)
        assertEquals(request, index.getDownload(request.id)?.request)
        assertTrue(cache.isCached(request.customCacheKey!!, 0, bytes.size.toLong()))
        assertFalse(app.getSharedPreferences("connection", 0).contains("origin"))
    }

    @Test fun playedOnlyCopiesOpenOfflineWithoutAnyDownloadOrRememberedServer() {
        val key = "played:saved/fixture"
        seed(key)
        cache.applyContentMetadataMutations(key, ContentMetadataMutations()
            .set(SONG_METADATA, encodeSong(TauonTrack(7, "Kept title", "Artist", "Album", 1000, true, false)))
            .set(SAVED_FROM_METADATA, "opaque"))
        model.listenOffline()
        pumpUntil { !model.busy && model.offline }
        val entry = model.saved.single()
        assertEquals(SavedSource.Played, entry.ref.source)
        assertTrue(entry.complete)
        assertNull(model.endpoint)
        assertTrue(model.allTracks.isEmpty())
        assertEquals(entry.ref.handle, entry.mediaItem().mediaId)
        index.getDownloads().use { assertEquals(0, it.count) }
        assertTrue(cache.isCached(key, 0, bytes.size.toLong()))
    }

    @Test fun oversizedLiveLibraryFallsBackToSavedCopiesWithTheActualResourceErrorStillVisible() {
        unknownCopy()
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        socket.soTimeout = 5000
        val worker = Executors.newSingleThreadExecutor()
        val replies = listOf("/api1/version" to "{\"version\":1}",
            "/api1/playlists" to ("{\"playlists\":[" + (0..2048).joinToString(",") {
                "{\"id\":\"$it\",\"name\":\"List\",\"count\":0}" } + "]}"))
        val serving = worker.submit {
            for ((path, json) in replies) socket.accept().use { client ->
                client.soTimeout = 5000
                val input = client.getInputStream().bufferedReader(Charsets.UTF_8)
                assertEquals("GET $path HTTP/1.1", input.readLine())
                while (!input.readLine().isNullOrEmpty()) { }
                val body = json.toByteArray(Charsets.UTF_8)
                client.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    write(body); flush()
                }
            }
        }
        try {
            model.address = "http://127.0.0.1:${socket.localPort}"
            model.connect()
            pumpUntil { !model.busy && model.offline }
            assertTrue(model.error.orEmpty().contains("resource limits"))
            assertTrue(model.error.orEmpty().contains("saved copies still play"))
            assertEquals(1, model.saved.size)
            assertTrue(model.saved.single().complete)
            assertTrue(model.allTracks.isEmpty())
            serving.get(5, TimeUnit.SECONDS)
        } finally { socket.close(); worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS) }
    }

    private fun unknownCopy(): DownloadRequest {
        val request = DownloadRequest.Builder("legacy", Uri.parse("opaque:old-source"))
            .setCustomCacheKey("legacy-key").setData(byteArrayOf(3, 4)).build()
        seed(requireNotNull(request.customCacheKey))
        index.putDownload(Download(request, Download.STATE_COMPLETED, 1, 1, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        return request
    }
    private fun seed(key: String) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes); cache.commitFile(file, bytes.size.toLong())
            cache.applyContentMetadataMutations(key,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        } finally { cache.releaseHoleSpan(hole) }
    }
    private fun pumpUntil(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(1)
        } while (System.nanoTime() < until)
        fail("Timed out waiting for the actual library coroutine/main-looper boundary")
    }
}
