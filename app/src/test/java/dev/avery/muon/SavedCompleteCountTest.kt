package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadRequest
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

/** Actual index/caches: count agrees with visible completeness without projecting metadata or covers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedCompleteCountTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var index: androidx.media3.exoplayer.offline.DefaultDownloadIndex
    private val bytes = byteArrayOf(1, 2, 3, 4)
    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        index = androidx.media3.exoplayer.offline.DefaultDownloadIndex(database, "saved_count")
        cache = SimpleCache(folders.newFolder(), NoOpCacheEvictor(), database)
        cache.checkInitialization()
    }
    @After fun tearDown() { try { cache.release() } finally { database.close() } }

    @Test fun visibleCompletedAndRetainedCopiesCountExactlyIncludingAliasesAndUnknownTags() {
        put("saved/normal", "saved/normal", Download.STATE_COMPLETED)
        put("alias-one", "shared", Download.STATE_COMPLETED)
        put("alias-two", "shared", Download.STATE_COMPLETED)
        val large = ByteArray(256 * 1024) { 3 }
        val retained = put("saved/retained", "saved/retained", Download.STATE_STOPPED, large, RETAINED_STOP_REASON)
        put("saved/other-stop", "saved/other-stop", Download.STATE_STOPPED, stop = 7)
        put("saved/removing", "saved/removing", Download.STATE_REMOVING)
        put("saved/failed", "saved/failed", Download.STATE_FAILED)
        put("saved/partial", "saved/partial", Download.STATE_COMPLETED)
        put("x".repeat(1200), "hidden", Download.STATE_COMPLETED)
        for (key in listOf("saved/normal", "shared", "saved/retained", "saved/other-stop", "saved/removing", "saved/failed", "hidden")) seed(key)
        seed("saved/partial", partial = true)
        val expected = savedInventory(SavedShelf.Phone, index, cache, PlayedClaims.none()) { false }
            .count { it.complete }.toLong()
        assertEquals(4L, expected)
        assertEquals(expected, countCompleteSavedCopies(SavedShelf.Phone, index, cache, true))
        assertEquals(retained.request, index.getDownload(retained.request.id)?.request)
        assertArrayEquals(large, index.getDownload(retained.request.id)?.request?.data)
        assertEquals(Download.STATE_STOPPED, index.getDownload(retained.request.id)?.state)
        assertArrayEquals(bytes, requireNotNull(cache.getCachedSpans("saved/retained").single().file).readBytes())
    }

    @Test fun completePlayedCopiesCountOnlyOnPhoneIncludingUnknownAndOversizedTags() {
        seed("played:known"); seed("played:unknown"); seed("played:partial", partial = true)
        val data = ByteArray(256 * 1024) { 7 }
        cache.applyContentMetadataMutations("played:unknown", ContentMetadataMutations().set(SONG_METADATA, data))
        // An index row claims this played key. The played copy stays visible/complete but not removable.
        put("odd", "played:known", Download.STATE_COMPLETED)
        val claims = PlayedClaims().apply { read(index) }
        val expected = savedInventory(SavedShelf.Phone, index, cache, claims) { false }.count { it.complete }.toLong()
        assertEquals(2L, expected)
        assertEquals(expected, countCompleteSavedCopies(SavedShelf.Phone, index, cache, true))
        assertEquals(0L, countCompleteSavedCopies(SavedShelf.Phone, index, cache, false))
        assertEquals(0L, countCompleteSavedCopies(SavedShelf.Card, index, cache, true))
        assertArrayEquals(data, cache.getContentMetadata("played:unknown").get(SONG_METADATA, null as ByteArray?))
    }

    @Test fun failedCursorClosesAndCannotPublishAPartialCount() {
        for (id in listOf("saved/a", "saved/b", "saved/c")) { put(id, id, Download.STATE_COMPLETED); seed(id) }
        var closed = false
        val faults = object : DownloadIndex by index {
            override fun getDownloads(vararg states: Int): DownloadCursor {
                val real = index.getDownloads(*states)
                var reads = 0
                return object : DownloadCursor by real {
                    override fun moveToNext(): Boolean {
                        if (++reads == 2) throw IOException("Injected count cursor failure")
                        return real.moveToNext()
                    }
                    override fun close() { closed = true; real.close() }
                }
            }
        }
        assertThrows(IOException::class.java) { countCompleteSavedCopies(SavedShelf.Phone, faults, cache, true) }
        assertTrue(closed)
        assertEquals(3L, countCompleteSavedCopies(SavedShelf.Phone, index, cache, true))
        assertEquals(3, savedInventory(SavedShelf.Phone, index, cache, PlayedClaims.none()) { false }.size)
    }

    private fun put(id: String, key: String, state: Int, data: ByteArray = byteArrayOf(0), stop: Int = 0): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://127.0.0.1:7814/api1/file/1"))
            .setCustomCacheKey(key).setData(data).build()
        return Download(request, state, 1, 1, bytes.size.toLong(), stop,
            if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN else Download.FAILURE_REASON_NONE).also(index::putDownload)
    }
    private fun seed(key: String, partial: Boolean = false) {
        val payload = if (partial) bytes.copyOf(2) else bytes
        val hole = requireNotNull(cache.startReadWrite(key, 0, payload.size.toLong()))
        try {
            val file = cache.startFile(key, 0, payload.size.toLong())
            file.writeBytes(payload); cache.commitFile(file, payload.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
    }
}
