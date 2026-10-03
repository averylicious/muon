package dev.avery.muon

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
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
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * #225: actual optional copy and maintenance calls, real cache/evictor and loopback OkHttp I/O.
 * A response-body gate keeps the production copier occupied without changing its executor or client.
 * These cover copy preservation and cancellation/maintenance ordering, not real-device performance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayedCopyCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var manager: DownloadManager
    private lateinit var server: GatedResponse
    private val storeField = OfflineStore::class.java.getDeclaredField("store").apply { isAccessible = true }
    private val copierField = OfflineStore::class.java.getDeclaredField("copier").apply { isAccessible = true }
    private var previousStore: Any? = null
    private var previousLimit = DEFAULT_CACHE_LIMIT
    private val oldPlayed = playedKey("http://127.0.0.1:7814/old")
    private val explicit = "http://127.0.0.1:7814/kept-download"
    private val seed = ByteArray(128) { (it % 113).toByte() }
    private val payload = ByteArray(4096) { (it % 251).toByte() }
    private val song = encodeSong(TauonTrack(7, "Fixture", "Artist", "Album", 3000, true, false))
    private val id get() = "http://127.0.0.1:${server.port}/7"
    private val copier get() = copierField.get(null) as ExecutorService

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        previousStore = storeField.get(null)
        previousLimit = PlayedCacheState.limit
        database = StandaloneDatabaseProvider(app)
        val evictor = PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {}
        cache = SimpleCache(folders.newFolder("cache"), evictor, database)
        cache.checkInitialization()
        manager = DownloadManager(app, DefaultDownloadIndex(database, "played_copy_fixture"),
            DownloaderFactory { error("Fixture must not start an explicit downloader") })
        val phone = Shelf(cache, manager, MuonDownloadService::class.java)
        val prefs = app.getSharedPreferences("played-copy-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        storeField.set(null, OfflineStore.Store(phone, DownloadArt(folders.newFolder("art")),
            evictor, prefs, database, {}, {}))
        server = GatedResponse(payload)
        seedCache(oldPlayed, seed)
        seedCache(explicit, seed)
    }

    @After fun tearDown() {
        try {
            server.release()
            // Wait for actual copy and queued maintenance before releasing cache/database resources.
            copier.submit {}.get(10, TimeUnit.SECONDS)
        } finally {
            server.close()
            storeField.set(null, previousStore)
            PlayedCacheState.limit = previousLimit
            manager.release()
            cache.release()
            database.close()
        }
    }

    @Test fun normalCopyKeepsExactBytesAndMetadataWithoutTouchingExplicitDownload() {
        startGatedCopy()
        assertFalse("An unfinished response must not be advertised as playable offline",
            OfflineStore.playedCopy(app, id))
        server.release()
        awaitCopier()
        assertTrue(OfflineStore.playedCopy(app, id))
        assertArrayEquals(payload, resourceBytes(playedKey(id)))
        assertArrayEquals(song, cache.getContentMetadata(playedKey(id)).get(SONG_METADATA, null as ByteArray?))
        assertArrayEquals(seed, resourceBytes(explicit))
        assertEquals("GET /api1/fileopus/7 HTTP/1.1", server.requestLine)
        server.assertCompleted()
    }

    @Test fun clearCancelsUnfinishedOptionalCopyBeforeRemovingPlayedBytesOnly() {
        startGatedCopy()
        server.allowDisconnect = true
        OfflineStore.clearPlayed(app)
        val afterClear = copier.submit {}
        afterClear.get(5, TimeUnit.SECONDS)
        assertFalse("Maintenance must finish without the server releasing its body", server.finished)
        server.release()
        assertTrue(cache.keys.none { it.startsWith(PLAYED_PREFIX) })
        assertArrayEquals(seed, resourceBytes(explicit))
        server.assertCompleted()
    }

    @Test fun resizeCancelsUnfinishedOptionalCopyBeforeApplyingNewBudget() {
        startGatedCopy()
        server.allowDisconnect = true
        OfflineStore.setCacheLimit(app, 64)
        val afterResize = copier.submit {}
        assertEquals(64L, PlayedCacheState.limit)
        assertEquals(64L, OfflineStore.current()!!.prefs.getLong("cacheLimit", -1))
        afterResize.get(5, TimeUnit.SECONDS)
        assertFalse("Resize must finish without the server releasing its body", server.finished)
        assertTrue(cache.keys.filter { it.startsWith(PLAYED_PREFIX) }.sumOf { key ->
            cache.getCachedSpans(key).sumOf { it.length }
        } <= 64)
        assertFalse(OfflineStore.playedCopy(app, id))
        server.release()
        assertArrayEquals(seed, resourceBytes(explicit))
        server.assertCompleted()
    }

    @Test fun truncatedResponseIsNotPlayableAndQueuedMaintenanceEventuallyRuns() {
        startGatedCopy()
        server.truncate = true
        server.release()
        awaitCopier()
        assertFalse(OfflineStore.playedCopy(app, id))
        OfflineStore.clearPlayed(app)
        awaitCopier()
        assertTrue(cache.keys.none { it.startsWith(PLAYED_PREFIX) })
        assertArrayEquals(seed, resourceBytes(explicit))
        server.assertCompleted()
    }

    @Test fun actualOptionalCopyRejectsDeclaredOversizeWithoutWaitingForBody() {
        // Exercise production copyPlayed wiring, not just the byte-policy helper in isolation.
        OfflineStore.current()!!.prefs.edit().putLong("cacheLimit", 64).commit()
        server.allowDisconnect = true
        startGatedCopy()
        awaitCopier()
        assertFalse("Byte rejection must finish without the server releasing its body", server.finished)
        assertFalse(OfflineStore.playedCopy(app, id))
        assertArrayEquals(seed, resourceBytes(explicit))
        server.release()
        server.assertCompleted()
    }

    private fun startGatedCopy() {
        OfflineStore.copyPlayed(app, id, song)
        assertTrue("The actual HTTP request must reach the fixture", server.started.await(5, TimeUnit.SECONDS))
        assertFalse(server.finished)
    }

    private fun awaitCopier() { copier.submit {}.get(10, TimeUnit.SECONDS) }

    private fun seedCache(key: String, bytes: ByteArray) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(key, ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, bytes.size.toLong())
        })
    }

    private fun resourceBytes(key: String): ByteArray = cache.getCachedSpans(key)
        .sortedBy { it.position }.fold(byteArrayOf()) { bytes, span -> bytes + requireNotNull(span.file).readBytes() }

    /** No DNS/LAN/device calls: a numeric loopback listener and one finite HTTP response. */
    private class GatedResponse(private val bytes: ByteArray) : AutoCloseable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val worker = Executors.newSingleThreadExecutor()
        private val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val port: Int get() = listener.localPort
        @Volatile var truncate = false
        @Volatile var allowDisconnect = false
        @Volatile var finished = false
        @Volatile var requestLine: String? = null
        @Volatile private var socket: Socket? = null
        private val work: Future<*> = worker.submit {
            listener.accept().use { client ->
                socket = client
                client.soTimeout = 5000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                requestLine = reader.readLine()
                while (!reader.readLine().isNullOrEmpty()) { /* HTTP headers */ }
                val output = client.getOutputStream()
                val headers = ("HTTP/1.1 200 OK\r\nContent-Type: audio/ogg\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                output.write(headers + bytes.copyOfRange(0, 32))
                output.flush()
                started.countDown()
                check(gate.await(10, TimeUnit.SECONDS)) { "Fixture response gate was not released" }
                if (!truncate && !allowDisconnect) { output.write(bytes, 32, bytes.size - 32); output.flush() }
            }
            finished = true
        }

        fun release() { gate.countDown() }
        fun assertCompleted() { work.get(5, TimeUnit.SECONDS); assertTrue(finished) }
        override fun close() {
            release()
            listener.close()
            socket?.close()
            worker.shutdownNow()
            check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "Fixture server worker still active" }
        }
    }
}
