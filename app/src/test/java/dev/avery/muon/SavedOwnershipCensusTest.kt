package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadCursor
import androidx.media3.exoplayer.offline.DownloadIndex
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

/**
 * #213 production ownership rules over real native-SQLite index rows and a real SimpleCache with the
 * production played-copy evictor: seeded legacy, aliased and malformed rows are never treated as sole
 * owners, and the played cache never evicts a key a row names, nor any before the index is read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedOwnershipCensusTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private val caches = ArrayList<SimpleCache>()
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() { database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }

    @After fun tearDown() { try { caches.forEach { it.release() } } finally { database.close() } }

    @Test fun aliasedMalformedAndPlayedKeyRowsAreNeverSoleOwners() {
        val index = DefaultDownloadIndex(database, "census")
        put(index, "saved/a", "saved/a")
        put(index, "http://192.168.1.10:7814/9", "saved/a") // An unknown row aliasing a new save's key.
        put(index, "http://192.168.1.10:7814/2", "http://192.168.1.10:7814/2") // Legacy, alone.
        put(index, "odd", "played:http://192.168.1.10:7814/3") // A row naming a played-copy key.
        put(index, "keyless", null) // Media3 falls back to the address as its key.
        put(index, "other-id", "different-key") // Key is not its own ID.
        val rows = rows(index)
        assertFalse("Aliased: removing it would delete the other row's bytes", soleOwner(rows, "saved/a"))
        assertFalse(soleOwner(rows, "http://192.168.1.10:7814/9"))
        assertTrue("A legacy row alone on its key is removable", soleOwner(rows, "http://192.168.1.10:7814/2"))
        assertFalse(soleOwner(rows, "odd"))
        assertFalse(soleOwner(rows, "keyless"))
        assertFalse(soleOwner(rows, "other-id"))
        assertFalse("A missing row owns nothing", soleOwner(rows, "missing"))
        assertEquals(setOf("played:http://192.168.1.10:7814/3"), PlayedClaims.keysIn(rows))
    }

    @Test fun aMoveNeverAddsASecondRowNamingAKeyOrRebindsOne() {
        val source = DefaultDownloadIndex(database, "source")
        val target = DefaultDownloadIndex(database, "target")
        val legacy = put(source, "http://192.168.1.10:7814/2", "http://192.168.1.10:7814/2")
        val fresh = put(source, "saved/b", "saved/b")
        // The target already holds an unknown row naming the legacy key under another ID.
        put(target, "unknown", "http://192.168.1.10:7814/2")
        // And the exact same request, as after an earlier partial move: no address or metadata rebinding.
        put(target, "saved/b", "saved/b")
        assertFalse(movable(legacy, rows(source), rows(target)))
        assertTrue(movable(fresh, rows(source), rows(target)))
        // Even byte-equal caches do not justify replacing another retained request's address or tags.
        val otherAddress = fresh.request.copyWithId(fresh.request.id).let {
            DownloadRequest.Builder(it.id, Uri.parse("http://192.168.1.10:7814/api1/fileopus/10"))
                .setCustomCacheKey(it.customCacheKey).setData(it.data).build()
        }
        target.putDownload(Download(otherAddress, Download.STATE_COMPLETED, 0L, 0L, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        assertFalse(movable(fresh, rows(source), rows(target)))
        val otherTags = DownloadRequest.Builder(fresh.request.id, fresh.request.uri)
            .setCustomCacheKey(fresh.request.customCacheKey).setData(byteArrayOf(9)).build()
        target.putDownload(Download(otherTags, Download.STATE_COMPLETED, 0L, 0L, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        assertFalse(movable(fresh, rows(source), rows(target)))
        // The same ID with another key there would be rebound by Media3's merge, so it is refused too.
        put(target, "saved/b", "rebound")
        assertFalse(movable(fresh, rows(source), rows(target)))
    }

    @Test fun inventoryMarksOnlySoleOwnersAndUnclaimedPlayedCopiesRemovable() {
        val index = DefaultDownloadIndex(database, "inventory")
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        put(index, "saved/a", "saved/a"); seed(cache, "saved/a")
        put(index, "http://192.168.1.10:7814/9", "saved/a")
        put(index, "odd", "played:claimed"); seed(cache, "played:claimed")
        seed(cache, "played:free")
        val claims = PlayedClaims().apply { ready(PlayedClaims.keysIn(rows(index))) }
        val entries = savedInventory(SavedShelf.Phone, rows(index), cache, claims) { false }
        assertTrue(entries.filter { it.ref.source == SavedSource.Download }.none { it.removable })
        val played = entries.filter { it.ref.source == SavedSource.Played }.associateBy { it.ref.key }
        assertFalse(requireNotNull(played["played:claimed"]).removable)
        assertTrue(requireNotNull(played["played:free"]).removable)
        // Both rows on the aliased key are listed; the row naming a played key cannot be a download handle,
        // so its bytes appear once, as the kept played copy. Nothing is deleted to list them.
        assertEquals(2, entries.count { it.ref.source == SavedSource.Download })
        assertTrue(cache.isCached("saved/a", 0, bytes.size.toLong()))
    }

    @Test fun theProductionEvictorNeverRemovesAClaimedKeyOrAnythingBeforeTheIndexIsRead() {
        val claims = PlayedClaims()
        val evictor = PlayedSongEvictor(1, claims::removable) {}
        val cache = cache(evictor)
        seed(cache, "played:claimed")
        seed(cache, "played:a")
        seed(cache, "played:b")
        // Over its one-byte limit, but the index has not been read: nothing at all is evicted.
        assertEquals(3, cache.keys.size)
        claims.ready(setOf("played:claimed"))
        evictor.resize(0)
        assertEquals(setOf("played:claimed"), cache.keys.filterTo(HashSet()) { cache.getCachedSpans(it).isNotEmpty() })
        assertTrue(cache.isCached("played:claimed", 0, bytes.size.toLong()))
    }

    @Test fun anUnlistedRemovingAliasStillProtectsTheListedSavedCopy() {
        val index = DefaultDownloadIndex(database, "removing_alias")
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val kept = put(index, "saved/kept", "saved/kept")
        seed(cache, kept.request.id)
        val alias = put(index, "removing-alias", kept.request.id)
        index.putDownload(Download(alias.request, Download.STATE_REMOVING, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        val census = rows(index)
        val entries = savedInventory(SavedShelf.Phone, census, cache, null) { false }
        assertEquals(listOf(kept.request.id), entries.map { it.ref.requestId })
        assertFalse(entries.single().removable)
        assertFalse(soleOwner(census, kept.request.id))
        assertTrue(cache.isCached(kept.request.id, 0, bytes.size.toLong()))
        assertEquals(Download.STATE_REMOVING, index.getDownload(alias.request.id)?.state)
    }

    @Test fun inventoryOwnershipVisitsGrowLinearlyAndDuplicateIdsRemainProtected() {
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val downloads = (0 until 2048).map { number ->
            val id = "saved/$number"
            val request = DownloadRequest.Builder(id, Uri.parse("http://192.168.1.10:7814/api1/fileopus/9"))
                .setCustomCacheKey(id).build()
            Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
                Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE)
        }
        var visits = 0
        val counted = object : AbstractList<Download>() {
            override val size get() = downloads.size
            override fun get(index: Int): Download = downloads[index].also { visits++ }
        }
        val entries = savedInventory(SavedShelf.Phone, counted, cache, null) { false }
        assertEquals(downloads.size, entries.size)
        assertTrue(entries.all { it.removable })
        // Counts production list visits, not wall time or a mirrored census implementation.
        assertTrue("Ownership must not rescan the whole index for each entry: $visits visits",
            visits <= 8 * downloads.size)
        assertTrue(cache.keys.isEmpty())
        val duplicate = downloads.first()
        val repeated = savedInventory(SavedShelf.Phone, listOf(duplicate, duplicate), cache, null) { false }
        assertEquals(2, repeated.size)
        assertTrue(repeated.none { it.removable })
        assertFalse(soleOwner(listOf(duplicate, duplicate), duplicate.request.id))
    }

    @Test fun oversizedDownloadMetadataIsNotProjectedAndItsStoredRecordAndAudioAreKept() {
        val index = DefaultDownloadIndex(database, "large_metadata")
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val row = put(index, "saved/large", "saved/large")
        val record = encodeSong(TauonTrack(42, "T".repeat(700_000), "Artist", "Album", 1000, true, false))
        val request = DownloadRequest.Builder(row.request.id, row.request.uri)
            .setCustomCacheKey(row.request.customCacheKey).setData(record).build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 0, 0, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        seed(cache, request.id)
        val entry = savedInventory(SavedShelf.Phone, rows(index), cache, null) { false }.single()
        assertNull("Do not retain a decoded oversized record", entry.song)
        assertTrue(entry.complete)
        assertTrue(entry.metadataTooLarge)
        assertEquals("Saved song (metadata too large)", entry.title())
        assertEquals(entry.ref.handle, entry.mediaItem().mediaId)
        assertNull(entry.mediaItem().mediaMetadata.extras?.getByteArray(SONG_EXTRA))
        assertArrayEquals(record, requireNotNull(index.getDownload(request.id)).request.data)
        assertArrayEquals(bytes, requireNotNull(cache.getCachedSpans(request.id).single().file).readBytes())
    }

    @Test fun playedMetadataPreflightPreservesOversizedBytesAndAcceptsTheExactEncodedBoundary() {
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val ordinary = TauonTrack(42, "", "Artist", "Album", 1000, true, false)
        val titleSize = TRACK_METADATA_MAX_BYTES - encodeSong(ordinary).size
        val exactSong = ordinary.copy(title = "T".repeat(titleSize))
        val exact = encodeSong(exactSong)
        assertEquals(TRACK_METADATA_MAX_BYTES, exact.size)
        val oversized = encodeSong(exactSong.copy(title = exactSong.title + "T"))
        val keys = listOf("played:exact", "played:oversized")
        for ((key, record) in keys.zip(listOf(exact, oversized))) {
            seed(cache, key)
            cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(SONG_METADATA, record))
        }
        val entries = savedInventory(SavedShelf.Phone, emptyList(), cache, PlayedClaims.none()) { false }
            .associateBy { it.ref.key }
        val accepted = requireNotNull(entries[keys[0]])
        assertEquals(exactSong, accepted.song)
        assertFalse(accepted.metadataTooLarge)
        val refused = requireNotNull(entries[keys[1]])
        assertNull(refused.song)
        assertTrue(refused.metadataTooLarge)
        assertTrue(refused.complete)
        assertArrayEquals(oversized, cache.getContentMetadata(keys[1]).get(SONG_METADATA, ByteArray(0)))
        assertArrayEquals(bytes, requireNotNull(cache.getCachedSpans(keys[1]).single().file).readBytes())
    }

    @Test fun streamedIndexInventoryIncludesHiddenOwnersAndClosesOneRewoundCursor() {
        val index = DefaultDownloadIndex(database, "streamed_census")
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val owner = put(index, "saved/owner", "saved/owner")
        seed(cache, owner.request.id)
        // This row cannot be represented by a SavedRef, yet must protect the visible row's bytes.
        val hidden = put(index, "hidden/" + "X".repeat(1200), owner.request.id)
        val cursor = index.getDownloads()
        var opens = 0
        var reads = 0
        var rewinds = 0
        val counted = object : DownloadIndex {
            override fun getDownload(id: String): Download? = index.getDownload(id)
            override fun getDownloads(vararg states: Int): DownloadCursor {
                opens++
                assertTrue(states.isEmpty())
                return object : DownloadCursor by cursor {
                    override fun getDownload(): Download { reads++; return cursor.download }
                    override fun moveToPosition(position: Int): Boolean {
                        if (position == -1) rewinds++
                        return cursor.moveToPosition(position)
                    }
                }
            }
        }
        val entries = savedInventory(SavedShelf.Phone, counted, cache, null) { false }
        assertEquals(1, opens)
        assertEquals(2, rewinds)
        assertEquals(4, reads)
        assertTrue(cursor.isClosed)
        assertEquals(listOf(owner.request.id), entries.map { it.ref.requestId })
        assertFalse("An undisplayed row still claims the audio", entries.single().removable)
        assertTrue(entries.single().complete)
        assertNotNull(index.getDownload(hidden.request.id))
        assertArrayEquals(bytes, requireNotNull(cache.getCachedSpans(owner.request.id).single().file).readBytes())
    }

    @Test fun streamedInventoryClosesCursorOnProjectionFailureWithoutChangingIndexOrAudio() {
        val index = DefaultDownloadIndex(database, "streamed_failure")
        val cache = cache(PlayedSongEvictor(DEFAULT_CACHE_LIMIT) {})
        val row = put(index, "saved/kept", "saved/kept")
        seed(cache, row.request.id)
        val cursor = index.getDownloads()
        val wrapped = object : DownloadIndex {
            override fun getDownload(id: String): Download? = index.getDownload(id)
            override fun getDownloads(vararg states: Int): DownloadCursor = cursor
        }
        try {
            savedInventory(SavedShelf.Phone, wrapped, cache, null) { throw IllegalStateException("cover read failed") }
            fail("A failed projection must not report a complete inventory")
        } catch (expected: IllegalStateException) { assertEquals("cover read failed", expected.message) }
        assertTrue(cursor.isClosed)
        assertEquals(row.request, index.getDownload(row.request.id)?.request)
        assertArrayEquals(bytes, requireNotNull(cache.getCachedSpans(row.request.id).single().file).readBytes())
    }

    private fun put(index: DefaultDownloadIndex, id: String, key: String?): Download {
        val request = DownloadRequest.Builder(id, Uri.parse("http://192.168.1.10:7814/api1/fileopus/9"))
            .setCustomCacheKey(key).setData(ByteArray(0)).build()
        return Download(request, Download.STATE_COMPLETED, 0L, 0L, bytes.size.toLong(),
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE).also(index::putDownload)
    }

    private fun rows(index: DefaultDownloadIndex): List<Download> {
        val rows = ArrayList<Download>()
        index.getDownloads().use { while (it.moveToNext()) rows += it.download }
        return rows
    }

    private fun cache(evictor: androidx.media3.datasource.cache.CacheEvictor): SimpleCache =
        SimpleCache(folders.newFolder(), evictor, database).also { it.checkInitialization(); caches += it }

    private fun seed(cache: SimpleCache, key: String) {
        val hole = requireNotNull(cache.startReadWrite(key, 0, bytes.size.toLong()))
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
            cache.applyContentMetadataMutations(key,
                ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        } finally { cache.releaseHoleSpan(hole) }
    }
}
