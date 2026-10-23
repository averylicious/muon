package dev.avery.muon

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
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

/** #213 preparation only: private test model, not an app API, persisted schema or identity fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedEntryInventoryPrototypeTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: TestShelf
    private lateinit var card: TestShelf
    private val origin = "http://192.168.1.10:7814"
    private val song = TauonTrack(42, "Saved", "Artist", "Album", 180000, true, false)
    private val id get() = downloadId(origin, song.id)
    private val bytes = byteArrayOf(1, 2, 3, 4)

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        phone = shelf("phone")
        card = shelf("card")
    }

    @After fun tearDown() {
        try { phone.cache.release() } finally {
            try { card.cache.release() } finally { database.close() }
        }
    }

    @Test fun sameIdAcrossShelvesAndPlayedCopiesRemainsThreeSeparateUnverifiedEntries() {
        explicit(phone, id, encodeSong(song))
        explicit(card, id, encodeSong(song))
        seed(phone, playedKey(id), encodeSong(song))
        val entries = inventory(phone) + inventory(card)
        assertEquals(3, entries.size)
        assertEquals(3, entries.map { it.handle }.toSet().size)
        assertEquals(2, entries.count { it.handle.kind == Kind.Explicit })
        assertEquals(1, entries.count { it.handle.kind == Kind.Played })
        assertTrue(entries.all { it.song == song && it.unverified })
        assertEquals(setOf(phone.cache.uid, card.cache.uid), entries.map { it.handle.cacheUid }.toSet())
        assertEquals(setOf(id, playedKey(id)), entries.map { it.handle.cacheKey }.toSet())
    }

    @Test fun opaqueCustomCacheKeyAndRequestIdDoNotBecomeTheDecodedSongId() {
        val key = "opaque-key/with:delimiters/042"
        explicit(phone, id, encodeSong(song.copy(id = 99)), key = key)
        val entry = inventory(phone).single()
        assertEquals(id, entry.handle.recordId)
        assertEquals(key, entry.handle.cacheKey)
        assertEquals(99L, requireNotNull(entry.song).id)
        assertEquals(bytes.size.toLong(), entry.cachedBytes)
        assertNotEquals(downloadId(origin, requireNotNull(entry.song).id), entry.handle.recordId)
    }

    @Test fun unknownMalformedAndPendingVersionTwoRecordsRemainAddressableAndByteExact() {
        // #248's pending muon-song-2 form: all five textual fields are UTF-8 Base64.
        // Main's v1 decoder cannot read it yet. Preserve it, rather than copying a new codec here.
        val v2 = escapedRecord(song.copy(title = "Saved\u0000title"))
        val raw = listOf(ByteArray(0), "future-song-record".toByteArray(),
            "muon-song-1\u0000broken".toByteArray(), v2)
        raw.forEachIndexed { n, data -> explicit(phone, "$origin/${100 + n}", data) }
        val entries = inventory(phone).associateBy { it.handle.recordId }
        raw.forEachIndexed { n, data ->
            val entry = requireNotNull(entries["$origin/${100 + n}"])
            assertArrayEquals(data, requireNotNull(entry.recordData))
            assertEquals(decodeSong(data), entry.song)
            assertTrue(entry.unverified)
            assertEquals(Coverage.ObservedFull, entry.coverage)
        }
        assertEquals(4, entries.size)
    }

    @Test fun absentMetadataAndUnindexedCacheBytesAreNotPrunedOrAssignedALiveIdentity() {
        seed(phone, playedKey(id), null)
        seed(phone, "unowned-cache-key", "unknown".toByteArray())
        val entries = inventory(phone)
        assertEquals(2, entries.size)
        val played = entries.single { it.handle.kind == Kind.Played }
        assertNull(played.recordData)
        assertNull(played.song)
        val orphan = entries.single { it.handle.kind == Kind.Unindexed }
        assertEquals("unowned-cache-key", orphan.handle.cacheKey)
        assertNull(orphan.handle.recordId)
        assertNull(orphan.song)
        assertEquals(8L, phone.cache.cacheSpace)
    }

    @Test fun completedIndexStateDoesNotProveBytesArePresentOrComplete() {
        explicit(phone, "$origin/1", encodeSong(song), payload = null)
        explicit(phone, "$origin/2", encodeSong(song), payload = bytes.copyOf(2))
        val entries = inventory(phone).associateBy { it.handle.recordId }
        assertEquals(Download.STATE_COMPLETED, entries["$origin/1"]?.downloadState)
        assertEquals(Coverage.Missing, entries["$origin/1"]?.coverage)
        assertEquals(Coverage.Partial, entries["$origin/2"]?.coverage)
        assertTrue(entries.values.all { it.unverified })
    }

    @Test fun queuedAndFailedRecordsRemainVisibleWithoutBecomingPlayableCopies() {
        explicit(phone, "$origin/1", encodeSong(song), state = Download.STATE_QUEUED, payload = null)
        explicit(phone, "$origin/2", ByteArray(0), state = Download.STATE_FAILED, payload = bytes.copyOf(2))
        val entries = inventory(phone)
        assertEquals(setOf(Download.STATE_QUEUED, Download.STATE_FAILED), entries.map { it.downloadState }.toSet())
        assertEquals(setOf(Coverage.Missing, Coverage.Partial), entries.map { it.coverage }.toSet())
    }

    @Test fun indexAndCacheMetadataDisagreementIsPreservedWithoutInventingVerification() {
        explicit(phone, id, encodeSong(song))
        val cacheData = encodeSong(song.copy(title = "Other retained label"))
        phone.cache.applyContentMetadataMutations(id, ContentMetadataMutations().set(SONG_METADATA, cacheData))
        val entry = inventory(phone).single()
        assertArrayEquals(encodeSong(song), requireNotNull(entry.recordData))
        assertArrayEquals(cacheData, requireNotNull(entry.cacheData))
        assertEquals(song, entry.song) // display suggestion only, never a content-identity decision.
        assertTrue(entry.unverified)
    }

    @Test fun unavailableCacheIsAnErrorRatherThanAnEmptySuccessfulInventory() {
        explicit(phone, id, encodeSong(song))
        phone.cache.release()
        try {
            inventory(phone)
            fail("An unavailable owner must not be presented as having no saved entries")
        } catch (_: IllegalStateException) { /* The prototype deliberately propagates this failure. */ }
    }

    @Test fun unknownLengthDoesNotPromoteExistingSpansToACompleteCopy() {
        seed(phone, playedKey(id), encodeSong(song))
        phone.cache.applyContentMetadataMutations(playedKey(id), ContentMetadataMutations().apply {
            ContentMetadataMutations.setContentLength(this, C.LENGTH_UNSET.toLong())
        })
        assertEquals(Coverage.UnknownLength, inventory(phone).single().coverage)
    }

    @Test fun repeatedInventoryPreservesIndexRecordsMetadataAndSpanFiles() {
        explicit(phone, id, encodeSong(song))
        seed(phone, playedKey(id), "unknown".toByteArray())
        val before = snapshot(phone)
        val first = inventory(phone)
        val second = inventory(phone)
        assertEquals(first.map { it.handle }, second.map { it.handle })
        assertEquals(before, snapshot(phone))
        assertArrayEquals(bytes, requireNotNull(phone.cache.getCachedSpans(id).single().file).readBytes())
    }

    private fun shelf(name: String): TestShelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        return TestShelf(name, cache, DefaultDownloadIndex(database, name))
    }

    private fun explicit(shelf: TestShelf, requestId: String, data: ByteArray,
        key: String = requestId, state: Int = Download.STATE_COMPLETED, payload: ByteArray? = bytes) {
        if (payload != null) seed(shelf, key, data, payload)
        val request = DownloadRequest.Builder(requestId, Uri.parse("$origin/api1/fileopus/42"))
            .setCustomCacheKey(key).setData(data).build()
        shelf.index.putDownload(Download(request, state, 0L, 0L, bytes.size.toLong(),
            Download.STOP_REASON_NONE, if (state == Download.STATE_FAILED) Download.FAILURE_REASON_UNKNOWN
            else Download.FAILURE_REASON_NONE))
    }

    private fun seed(shelf: TestShelf, key: String, data: ByteArray?, payload: ByteArray = bytes) {
        val hole = shelf.cache.startReadWrite(key, 0L, payload.size.toLong())
        try {
            val file = shelf.cache.startFile(key, 0L, payload.size.toLong())
            file.writeBytes(payload)
            shelf.cache.commitFile(file, payload.size.toLong())
            val metadata = ContentMetadataMutations()
            if (data != null) metadata.set(SONG_METADATA, data)
            ContentMetadataMutations.setContentLength(metadata, bytes.size.toLong())
            shelf.cache.applyContentMetadataMutations(key, metadata)
        } finally { shelf.cache.releaseHoleSpan(hole) }
    }

    private fun escapedRecord(track: TauonTrack): ByteArray {
        val text = listOf(track.title, track.artist, track.album, track.albumArtist, track.trackNumber)
            .map { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        return listOf("muon-song-2", track.id.toString(), text[0], text[1], text[2], text[3],
            track.durationMs.toString(), text[4]).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun snapshot(shelf: TestShelf): List<String> {
        val records = ArrayList<String>()
        shelf.index.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                records += "${d.request.id}|${d.state}|${d.request.customCacheKey}|${d.request.data.contentToString()}"
            }
        }
        shelf.cache.keys.sorted().forEach { key ->
            val metadata = shelf.cache.getContentMetadata(key)
            records += "$key|${ContentMetadata.getContentLength(metadata)}|" +
                metadata.get(SONG_METADATA, null as ByteArray?)?.contentToString()
            shelf.cache.getCachedSpans(key).forEach { span ->
                records += "${span.position}|${span.length}|${span.file?.name}|${span.file?.readBytes()?.contentToString()}"
            }
        }
        return records.sorted()
    }

    private data class TestShelf(val location: String, val cache: SimpleCache, val index: DefaultDownloadIndex)
    private enum class Kind { Explicit, Played, Unindexed }
    private enum class Coverage { Missing, Partial, UnknownLength, ObservedFull }
    // UID distinguishes fixture caches; it is NOT authenticated card identity or a production lease.
    private data class Handle(val location: String, val cacheUid: Long, val kind: Kind,
        val recordId: String?, val cacheKey: String) {
        init { require(location.isNotBlank()); require(cacheKey.isNotEmpty()) }
    }
    private data class Entry(val handle: Handle, val recordData: ByteArray?, val cacheData: ByteArray?,
        val song: TauonTrack?, val downloadState: Int?, val cachedBytes: Long, val coverage: Coverage) {
        val unverified: Boolean get() = true
    }

    /** Synchronous test-local observation of pre-initialized disposable caches. Errors propagate. */
    private fun inventory(shelf: TestShelf): List<Entry> {
        val result = ArrayList<Entry>()
        val indexedKeys = HashSet<String>()
        fun entry(kind: Kind, recordId: String?, key: String, data: ByteArray?, state: Int?): Entry {
            val cacheData = shelf.cache.getContentMetadata(key).get(SONG_METADATA, null as ByteArray?)?.copyOf()
            val spans = shelf.cache.getCachedSpans(key)
            val total = spans.sumOf { it.length }
            val length = ContentMetadata.getContentLength(shelf.cache.getContentMetadata(key))
            val coverage = when {
                spans.isEmpty() -> Coverage.Missing
                length == C.LENGTH_UNSET.toLong() || length <= 0 -> Coverage.UnknownLength
                shelf.cache.isCached(key, 0L, length) -> Coverage.ObservedFull
                else -> Coverage.Partial
            }
            return Entry(Handle(shelf.location, shelf.cache.uid, kind, recordId, key), data?.copyOf(), cacheData,
                data?.let(::decodeSong), state, total, coverage)
        }
        shelf.index.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                // Production requests set customCacheKey. The fallback is only for legacy progressive requests.
                val key = d.request.customCacheKey ?: d.request.uri.toString()
                indexedKeys += key
                result += entry(Kind.Explicit, d.request.id, key, d.request.data, d.state)
            }
        }
        shelf.cache.keys.sorted().filterNot { it in indexedKeys }.forEach { key ->
            val data = shelf.cache.getContentMetadata(key).get(SONG_METADATA, null as ByteArray?)
            result += entry(if (key.startsWith(PLAYED_PREFIX)) Kind.Played else Kind.Unindexed,
                null, key, data, null)
        }
        return result
    }
}
