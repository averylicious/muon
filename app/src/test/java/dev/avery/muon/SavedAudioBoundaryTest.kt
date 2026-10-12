@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloaderFactory
import java.io.File
import java.io.IOException
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

/** Native legacy controls plus a cache-free read backend: production consumers need no aggregate Cache.
 * No partition switch, migration, network, phone or physical memory measurement is exercised here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedAudioBoundaryTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: DefaultDownloadIndex
    private val payload = byteArrayOf(1, 2, 3, 4)
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(app)
        cache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        index = DefaultDownloadIndex(database, "read_boundary")
    }
    @After fun tearDown() { try { cache.release() } finally { database.close() } }

    @Test fun legacyInspectionPreservesCoverageMetadataAndMetadataOnlyNames() {
        seed("full", payload, 4)
        seed("partial", payload.copyOf(2), 4)
        seed("unknown", payload, null)
        cache.applyContentMetadataMutations("metadata-only", ContentMetadataMutations().set("kept", "value"))
        val audio = LegacySavedAudio(cache)
        assertEquals(SavedCoverage.Full, audio.inspect("full").coverage)
        assertEquals(SavedCoverage.Partial, audio.inspect("partial").coverage)
        assertEquals(SavedCoverage.UnknownLength, audio.inspect("unknown").coverage)
        assertEquals(SavedCoverage.Missing, audio.inspect("absent").coverage)
        assertEquals(4L, audio.inspect("full").bytes)
        assertEquals("value", audio.inspect("metadata-only").metadata.get("kept", ""))
        assertTrue(audio.contains("metadata-only"))
        assertFalse(audio.contains("absent"))
        val keys = mutableListOf<String>()
        audio.forEachKey { keys += it; false }
        assertEquals(listOf(cache.keys.sorted().first()), keys)
        assertArrayEquals(payload, requireNotNull(cache.getCachedSpans("full").single().file).readBytes())
    }

    @Test fun realLegacyReaderCannotFillAMissingSpanOrChangeItsDownloadRecord() {
        seed("saved/partial", payload.copyOf(2), 4)
        val row = put("saved/partial")
        val audio = LegacySavedAudio(cache)
        val source = audio.source.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri("muon-saved:fixture").setKey(row.request.id).build())
            val buffer = ByteArray(4)
            assertEquals(2, source.read(buffer, 0, buffer.size))
            assertThrows(IOException::class.java) { source.read(buffer, 0, buffer.size) }
        } finally { source.close() }
        assertEquals(row.request, index.getDownload(row.request.id)?.request)
        assertEquals(Download.STATE_COMPLETED, index.getDownload(row.request.id)?.state)
        assertEquals(2L, audio.inspect(row.request.id).bytes)
        assertArrayEquals(payload.copyOf(2), requireNotNull(cache.getCachedSpans(row.request.id).single().file).readBytes())
    }

    @Test fun inventoryAndCountUseOnlySelectedKeysAndTheSuppliedPlayedCensus() {
        seed("saved/one", payload, 4); put("saved/one")
        seed("saved/two", payload, 4); put("saved/two")
        seed("saved/removing", payload, 4); put("saved/removing", Download.STATE_REMOVING)
        seed("played:one", payload, 4)
        cache.applyContentMetadataMutations("played:one", ContentMetadataMutations().set(SAVED_FROM_METADATA, "http://127.0.0.1:7814/1"))
        val inspected = mutableListOf<String>()
        val legacy = LegacySavedAudio(cache)
        val audio = object : SavedAudio by legacy {
            override fun inspect(key: String): SavedAudioState { inspected += key; return legacy.inspect(key) }
            override fun forEachKey(visit: (String) -> Boolean) { error("Must use the bounded disk census") }
        }
        val entries = mutableListOf<SavedEntry>()
        forEachSavedEntry(SavedShelf.Phone, index, audio, PlayedClaims.none(), { false },
            include = { it.key != "saved/two" }, playedKeys = { visit -> visit("played:one"); Unit },
            emit = entries::add)
        assertEquals(listOf("saved/one", "played:one"), inspected)
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.complete })
        inspected.clear()
        assertEquals(3L, countCompleteSavedCopies(SavedShelf.Phone, index, audio, true) { visit -> visit("played:one"); Unit })
        assertEquals(setOf("saved/one", "saved/two", "played:one"), inspected.toSet())
        assertEquals(Download.STATE_REMOVING, index.getDownload("saved/removing")?.state)
    }

    @Test fun inspectionFailurePropagatesAndClosesTheActualIndexCursor() {
        seed("saved/one", payload, 4); put("saved/one")
        var closed = false
        val tracking = object : DownloadIndex by index {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val cursor = index.getDownloads(*states)
                return object : DownloadCursor by cursor {
                    override fun close() { closed = true; cursor.close() }
                }
            }
        }
        val audio = object : SavedAudio by LegacySavedAudio(cache) {
            override fun inspect(key: String): SavedAudioState = throw IOException("Injected backend refusal")
        }
        assertThrows(IOException::class.java) { savedInventory(SavedShelf.Phone, tracking, audio, null) { false } }
        assertTrue(closed)
        closed = false
        assertThrows(IOException::class.java) { countCompleteSavedCopies(SavedShelf.Phone, tracking, audio, false) }
        assertTrue(closed)
        assertNotNull(index.getDownload("saved/one"))
        assertEquals(4L, savedCoverage(cache, "saved/one").second)
    }

    @Test fun offlinePlaybackUsesTheShelfBackendAndKeepsLiveStreamingSeparate() {
        val manager = DownloadManager(app, index, DownloaderFactory { error("No download may start") })
        val saved = Bytes(byteArrayOf(7, 8))
        val live = Bytes(byteArrayOf(9))
        val audio = object : SavedAudio by LegacySavedAudio(cache) {
            override val source = DataSource.Factory { saved }
        }
        val shelf = Shelf(cache, manager, MuonDownloadService::class.java, audio = audio)
        shelf.stream = DataSource.Factory { live }
        val source = OfflineDataSource { routeOfflineRequest(it, shelf, null) }
        try {
            val ref = requireNotNull(SavedRef.download(SavedShelf.Phone, "saved/backend", "saved/backend"))
            assertEquals(2L, source.open(DataSpec.Builder().setUri(ref.handle).build()))
            assertEquals(ref.key, saved.spec?.key)
            assertEquals(0, live.opens)
            assertEquals(2, source.read(ByteArray(4), 0, 4))
            source.close()
            assertEquals(1, saved.closes)
            assertEquals(1L, source.open(DataSpec.Builder().setUri("http://127.0.0.1:7814/api1/file/1").build()))
            assertEquals(1, live.opens)
            source.close()
            assertEquals(1, live.closes)
            assertTrue(cache.keys.isEmpty())
            assertNull(index.getDownload("saved/backend"))
        } finally { source.close(); manager.release() }
    }

    private fun put(id: String, state: Int = Download.STATE_COMPLETED): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/1"))
            .setCustomCacheKey(id).build()
        return Download(request, state, 1, 1, 4, 0, Download.FAILURE_REASON_NONE).also(index::putDownload)
    }
    private fun seed(key: String, bytes: ByteArray, length: Long?) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes); cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        if (length != null) cache.applyContentMetadataMutations(key,
            ContentMetadataMutations.setContentLength(ContentMetadataMutations(), length))
    }
    private class Bytes(private val bytes: ByteArray) : DataSource {
        var opens = 0; var closes = 0; var spec: DataSpec? = null; private var position = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { opens++; spec = dataSpec; position = 0; return bytes.size.toLong() }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position == bytes.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count); position += count
            return count
        }
        override fun getUri(): Uri? = spec?.uri
        override fun close() { closes++; spec = null }
    }
}
