package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
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

/** #213 characterization: the mismatch assertions describe the existing bug, NOT an identity fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RetainedIdentityCharacterizationTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private lateinit var manager: DownloadManager
    private lateinit var shelf: Shelf
    private val endpoint = ServerEndpoint.parse("http://192.168.1.10:7814")
    private val old = TauonTrack(42, "Retained A", "Artist A", "Album A", 180000, true, false)
    private val replacement = old.copy(title = "Live B", artist = "Artist B", album = "Album B")
    private val payloadA = byteArrayOf(1, 2, 3, 4)
    private val id get() = downloadId(endpoint.origin, old.id)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder("phone"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database)
        // No downloader is started; the manager only supplies its actual download index to Shelf.
        manager = DownloadManager(RuntimeEnvironment.getApplication(), index,
            DefaultDownloaderFactory(androidx.media3.datasource.cache.CacheDataSource.Factory().setCache(cache),
                java.util.concurrent.Executor { it.run() }))
        shelf = Shelf(cache, manager, MuonDownloadService::class.java)
    }

    @After fun tearDown() {
        try { manager.release(); cache.release() } finally { database.close() }
    }

    @Test fun liveReplacementStillReadsExplicitRetainedAudioAndIndexNamesOldSong() {
        downloadOldSong()
        val item = replacement.mediaItem(endpoint)
        assertEquals(replacement, decodeSong(requireNotNull(item.mediaMetadata.extras?.getByteArray(SONG_EXTRA))))
        assertNull("No current song identity reaches the progressive DataSpec", item.localConfiguration?.customCacheKey)
        val spec = DataSpec.Builder().setUri(requireNotNull(item.localConfiguration).uri).build()
        val routed = routeOfflineRequest(spec, shelf, listOf(shelf), offline = false)
        assertSame(shelf, routed.first)
        assertEquals(id, routed.second.key)
        assertEquals(old, decodeSong(requireNotNull(index.getDownload(id)).request.data))
        // The production OfflineDataSource reads the actual retained spans, without network access.
        val source = OfflineDataSource { request -> routeOfflineRequest(request, shelf, listOf(shelf), false) }
        try {
            assertEquals(payloadA.size.toLong(), source.open(spec))
            val read = ByteArray(payloadA.size)
            assertEquals(payloadA.size, source.read(read, 0, read.size))
            assertArrayEquals(payloadA, read)
        } finally { source.close() }
    }

    @Test fun ordinaryMatchingDownloadUsesTheSameRetainedRoute() {
        downloadOldSong()
        val result = routeOfflineRequest(stream(old), shelf, listOf(shelf), offline = false)
        assertEquals(id, result.second.key)
        assertEquals(endpoint.url("/api1/fileopus/42"), result.second.uri.toString())
        assertTrue(shelf.completed(id))
    }

    @Test fun offlinePlayedCopyAlsoSelectsOldBytesForAReusedLiveId() {
        seed(playedKey(id), encodeSong(old))
        val result = routeOfflineRequest(stream(replacement), shelf, listOf(shelf), offline = true)
        assertEquals(playedKey(id), result.second.key)
        assertEquals(old, decodeSong(requireNotNull(cache.getContentMetadata(playedKey(id))
            .get(SONG_METADATA, null as ByteArray?))))
        assertTrue(hasPlayedCopy(cache, id))
    }

    @Test fun onlinePlayedCopyAndDifferentOriginDoNotRedirect() {
        seed(playedKey(id), encodeSong(old))
        val spec = stream(replacement)
        assertSame(spec, routeOfflineRequest(spec, shelf, listOf(shelf), offline = false).second)
        downloadOldSong()
        val other = DataSpec.Builder().setUri("http://192.168.1.11:7814/api1/file/42").build()
        assertSame(other, routeOfflineRequest(other, shelf, listOf(shelf), offline = true).second)
    }

    private fun stream(track: TauonTrack): DataSpec =
        DataSpec.Builder().setUri(requireNotNull(track.mediaItem(endpoint).localConfiguration).uri).build()

    private fun downloadOldSong() {
        seed(id, encodeSong(old))
        val request = DownloadRequest.Builder(id, Uri.parse(endpoint.url("/api1/fileopus/42")))
            .setCustomCacheKey(id).setData(encodeSong(old)).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 0L, 0L, payloadA.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun seed(key: String, song: ByteArray) {
        val hole = cache.startReadWrite(key, 0L, payloadA.size.toLong())
        try {
            val file = cache.startFile(key, 0L, payloadA.size.toLong())
            file.writeBytes(payloadA)
            cache.commitFile(file, payloadA.size.toLong())
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(SONG_METADATA, song).apply {
                ContentMetadataMutations.setContentLength(this, payloadA.size.toLong())
            })
        } finally { cache.releaseHoleSpan(hole) }
    }
}
