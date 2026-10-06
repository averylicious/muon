package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * #213 preparation only (docs/audits/2026-10-05-saved-production-boundary.md, "Next bounded task"):
 * a prospective key/cover/alias ownership rule, exercised against real disposable Media3 index and
 * cache records and real DownloadArt files. Test-local; not an app API, schema, migration or #213 fix.
 * An opaque key or a matching tag is never identity, authentication or proof of the right artwork.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedOwnershipControlTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var phone: TestShelf
    private lateinit var card: TestShelf
    private val origin = "http://192.168.1.10:7814"
    private val song = TauonTrack(42, "Saved", "Artist", "Album", 180000, true, false)
    private val live get() = downloadId(origin, song.id)
    private val legacyBytes = byteArrayOf(1, 2, 3, 4)
    private val freshBytes = byteArrayOf(9, 8, 7, 6)

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

    @Test fun reusingTheLiveIdAsRequestIdReplacesTheLegacyRecordAndOrphansItsBytes() {
        // Why a prospective save needs its own request ID as well as its own key: the index keeps one
        // row per request ID, so a second save under the live ID silently replaces legacy A's row.
        save(phone, live, live, legacyBytes, encodeSong(song))
        save(phone, live, "fresh-key", freshBytes, encodeSong(song.copy(title = "Fresh")))
        val rows = records(phone)
        assertEquals(1, rows.size)
        assertEquals("fresh-key", rows.single().request.customCacheKey)
        assertEquals("Fresh", decodeSong(rows.single().request.data)?.title)
        // A's bytes survive only as an unindexed key that nothing owns any more.
        assertArrayEquals(legacyBytes, bytes(phone, live))
        assertTrue(rows.none { it.request.customCacheKey == live })
    }

    @Test fun freshSaveWithADistinctOpaqueKeyKeepsLegacyAAndSeparateBButProvesNoIdentity() {
        save(phone, live, live, legacyBytes, encodeSong(song))
        // An unindexed leftover already holds the first candidate key; it must be skipped, not adopted.
        seed(phone, "saved-entry-1", byteArrayOf(5, 5), null)
        val leftover = snapshot(phone).filter { it.startsWith("saved-entry-1|") || it.startsWith("0|2|") }
        val key = requireNotNull(prospectiveKey(phone))
        assertEquals("saved-entry-2", key)
        save(phone, key, key, freshBytes, encodeSong(song))

        val rows = records(phone).associateBy { it.request.id }
        assertEquals(setOf(live, key), rows.keys)
        assertArrayEquals(legacyBytes, bytes(phone, live))
        assertArrayEquals(freshBytes, bytes(phone, key))
        assertArrayEquals(byteArrayOf(5, 5), bytes(phone, "saved-entry-1"))
        assertTrue(snapshot(phone).containsAll(leftover))
        // Identical tags over different bytes: neither the tag nor the key says which is the live song.
        assertEquals(decodeSong(rows.getValue(live).request.data), decodeSong(rows.getValue(key).request.data))
        assertFalse(legacyBytes.contentEquals(freshBytes))
        // Nor is the key authentication: any writer can index another row that names it.
        save(phone, "forged", key, null, encodeSong(song.copy(id = 7)))
        assertEquals(2, records(phone).count { it.request.customCacheKey == key })
        assertEquals(Plan.Refused(Reason.Aliased), planRemoval(phone, key))
    }

    @Test fun unconditionalRemovalOfOneAliasDeletesBytesAnotherCompletedRecordStillClaims() {
        // The real downloader removal, then the index row: the steps DownloadManager takes to remove.
        save(phone, "entry-A", "shared-key", legacyBytes, encodeSong(song))
        save(phone, "entry-B", "shared-key", null, encodeSong(song))
        removeUnconditionally(phone, "entry-A")
        val survivor = records(phone).single()
        assertEquals("entry-B", survivor.request.id)
        assertEquals(Download.STATE_COMPLETED, survivor.state)
        assertTrue("B still claims a complete copy whose bytes are gone", phone.cache.getCachedSpans("shared-key").isEmpty())
        assertEquals(0L, phone.cache.cacheSpace)
    }

    @Test fun conservativeRuleRefusesAliasedRemovalButRemovesASoleOwnerExactly() {
        save(phone, "entry-A", "shared-key", legacyBytes, encodeSong(song))
        save(phone, "entry-B", "shared-key", null, encodeSong(song))
        // A legacy row without a custom key falls back to its URI; it aliases that key just the same.
        save(phone, "entry-C", "only-key", freshBytes, encodeSong(song))
        save(phone, "legacy-uri", null, null, encodeSong(song), uri = "only-key")
        save(phone, "entry-D", "sole-key", byteArrayOf(3, 3, 3), encodeSong(song))
        val before = snapshot(phone)
        for (target in listOf("entry-A", "entry-B", "entry-C")) {
            assertEquals(target, Plan.Refused(Reason.Aliased), planRemoval(phone, target))
        }
        assertEquals(before, snapshot(phone))

        val exact = planRemoval(phone, "entry-D")
        assertEquals(Plan.Exact("entry-D", "sole-key"), exact)
        execute(phone, exact as Plan.Exact)
        assertTrue(phone.cache.getCachedSpans("sole-key").isEmpty())
        val after = snapshot(phone)
        assertEquals(before.filterNot { "sole-key" in it || "entry-D" in it || it.startsWith("0|3|") }, after)
        assertArrayEquals(legacyBytes, bytes(phone, "shared-key"))
        assertArrayEquals(freshBytes, bytes(phone, "only-key"))
    }

    @Test fun missingMalformedInFlightOrUncensusedOwnersNeverBecomeExclusive() {
        save(phone, "no-key", null, legacyBytes, encodeSong(song), uri = "uri-fallback-key")
        save(phone, "queued", "queued-key", legacyBytes.copyOf(2), encodeSong(song), state = Download.STATE_QUEUED)
        save(phone, "downloading", "dl-key", legacyBytes.copyOf(2), encodeSong(song), state = Download.STATE_DOWNLOADING)
        save(phone, "sole", "sole-key", freshBytes, ByteArray(0))
        val before = snapshot(phone)
        assertEquals(Plan.Refused(Reason.MissingOwner), planRemoval(phone, "never-indexed"))
        assertEquals(Plan.Refused(Reason.MalformedOwner), planRemoval(phone, "no-key"))
        assertEquals(Plan.Refused(Reason.InFlight), planRemoval(phone, "queued"))
        assertEquals(Plan.Refused(Reason.InFlight), planRemoval(phone, "downloading"))
        // An undecodable tag does not stop exact removal; it is ownership, not identity, being decided.
        assertTrue(planRemoval(phone, "sole") is Plan.Exact)
        // But a declared co-owner index that cannot be read leaves the owner set unknown.
        val absent = { throw IOException("Declared co-owner index is unavailable") }
        assertEquals(Plan.Refused(Reason.IncompleteCensus), planRemoval(phone, "sole", listOf(absent)))
        assertEquals(before, snapshot(phone))
    }

    @Test fun aRecordOnAnotherShelfNeverOwnsThisShelfsBytesOrMakesThemRemovable() {
        // As after an interrupted move: the card holds the record, the phone only leftover bytes.
        save(card, live, live, legacyBytes, encodeSong(song))
        seed(phone, live, legacyBytes, encodeSong(song))
        val phoneBefore = snapshot(phone)
        assertEquals(Plan.Refused(Reason.MissingOwner), planRemoval(phone, live))

        val exact = planRemoval(card, live) as Plan.Exact
        execute(card, exact)
        assertTrue(records(card).isEmpty())
        assertTrue(card.cache.getCachedSpans(live).isEmpty())
        // Removing the card's entry leaves the phone's unowned copy exactly as it was: not adopted, not removed.
        assertEquals(phoneBefore, snapshot(phone))
        assertEquals(Plan.Refused(Reason.MissingOwner), planRemoval(phone, live))
    }

    @Test fun todaysCoverIsOneFilePerLiveIdSharedByEveryShelfsRecord() {
        // Characterization of DownloadArt as it is: the name hashes only origin/ID, so phone and card
        // records of one live ID share a single cover, and remove(id) takes it from both.
        val art = DownloadArt(folders.newFolder("downloads-art"))
        save(phone, live, live, legacyBytes, encodeSong(song))
        save(card, live, live, freshBytes, encodeSong(song))
        val legacy = legacyCover(art, live, byteArrayOf(7, 7))
        assertEquals(1, legacy.parentFile!!.listFiles()!!.size)
        assertArrayEquals(byteArrayOf(7, 7), art.forArtwork("$origin/api1/pic/medium/${song.id}"))
        art.remove(live)
        assertFalse(art.has(live))
        // Both records remain, now without the cover either of them was shown with.
        assertEquals(1, records(phone).size)
        assertEquals(1, records(card).size)
    }

    @Test fun prospectiveCoversAreOwnedPerEntryAndNeverDeleteOrAdoptTheLegacyCover() {
        val art = DownloadArt(folders.newFolder("downloads-art"))
        val covers = folders.newFolder("saved-entry-art")
        save(phone, live, live, legacyBytes, encodeSong(song))
        val legacy = legacyCover(art, live, byteArrayOf(7, 7))
        save(phone, "saved-entry-1", "saved-entry-1", freshBytes, encodeSong(song))
        save(phone, "saved-entry-2", "saved-entry-2", freshBytes.copyOf(2), encodeSong(song))
        entryCover(covers, phone, "saved-entry-1").writeBytes(byteArrayOf(1, 1))

        assertArrayEquals(byteArrayOf(1, 1), entryCover(covers, phone, "saved-entry-1").readBytes())
        // An entry without its own cover has none: the live ID's legacy cover is not adopted for it.
        assertFalse(entryCover(covers, phone, "saved-entry-2").exists())
        assertNotEquals(entryCover(covers, phone, "saved-entry-1"), entryCover(covers, card, "saved-entry-1"))

        execute(phone, planRemoval(phone, "saved-entry-1") as Plan.Exact)
        entryCover(covers, phone, "saved-entry-1").delete() // Exact: only the removed entry's own cover.
        assertTrue(covers.listFiles()!!.isEmpty())
        // The legacy cover's owner set is unknown (any shelf's row for the live ID); it is left alone.
        assertArrayEquals(byteArrayOf(7, 7), legacy.readBytes())
        assertArrayEquals(byteArrayOf(7, 7), art.forArtwork("$origin/api1/pic/medium/${song.id}"))
        assertEquals(setOf(live, "saved-entry-2"), records(phone).map { it.request.id }.toSet())
        assertArrayEquals(legacyBytes, bytes(phone, live))
    }

    // --- Prospective ownership rule (test-local) ---

    private enum class Reason { MissingOwner, MalformedOwner, InFlight, Aliased, IncompleteCensus }
    private sealed interface Plan {
        data class Exact(val requestId: String, val key: String) : Plan
        data class Refused(val reason: Reason) : Plan
    }

    /**
     * Bytes may be removed only for a row in this shelf's own index that has an explicit key, is not
     * in flight, and is the only row claiming that key among this index and every declared co-owner
     * index of the same cache, all of which must be readable. Anything else refuses and mutates nothing.
     */
    private fun planRemoval(shelf: TestShelf, requestId: String,
        coOwners: List<() -> List<Download>> = emptyList()): Plan {
        val own = records(shelf)
        val target = own.singleOrNull { it.request.id == requestId } ?: return Plan.Refused(Reason.MissingOwner)
        val key = target.request.customCacheKey
        if (key.isNullOrEmpty()) return Plan.Refused(Reason.MalformedOwner)
        if (target.state !in setOf(Download.STATE_COMPLETED, Download.STATE_FAILED, Download.STATE_STOPPED)) {
            return Plan.Refused(Reason.InFlight)
        }
        val others = try { coOwners.flatMap { it() } } catch (_: IOException) {
            return Plan.Refused(Reason.IncompleteCensus)
        }
        val claims = (own + others).count { (it.request.customCacheKey ?: it.request.uri.toString()) == key }
        return if (claims == 1) Plan.Exact(requestId, key) else Plan.Refused(Reason.Aliased)
    }

    /** A new key that no row names and no cached resource holds; a leftover is skipped, never adopted. */
    private fun prospectiveKey(shelf: TestShelf): String? {
        val taken = shelf.cache.keys + records(shelf).flatMap {
            listOfNotNull(it.request.id, it.request.customCacheKey, it.request.uri.toString())
        }
        return (1..1000).map { "saved-entry-$it" }.firstOrNull { it !in taken }
    }

    private fun execute(shelf: TestShelf, plan: Plan.Exact) {
        val download = requireNotNull(shelf.index.getDownload(plan.requestId))
        assertEquals(plan.key, download.request.customCacheKey)
        removeUnconditionally(shelf, plan.requestId)
    }

    /** What removal does today, whoever else claims the key: the real downloader's remove, then the row. */
    private fun removeUnconditionally(shelf: TestShelf, requestId: String) {
        val request = requireNotNull(shelf.index.getDownload(requestId)).request
        DefaultDownloaderFactory(CacheDataSource.Factory().setCache(shelf.cache), Runnable::run)
            .createDownloader(request).remove()
        shelf.index.removeDownload(requestId)
    }

    /** A per-entry cover name: shelf and request ID, never the live ID. The location is a label, not authenticated identity. */
    private fun entryCover(dir: File, shelf: TestShelf, requestId: String) = File(dir, sha256("${shelf.location}\u0000$requestId"))

    /** Stored as DownloadArt.fetch stores it, without the network (as DownloadArtTest does). */
    private fun legacyCover(art: DownloadArt, id: String, cover: ByteArray): File {
        val dir = folders.root.resolve("downloads-art")
        return File(dir, sha256(id)).also { it.writeBytes(cover); assertTrue(art.has(id)) }
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    // --- Real disposable fixtures ---

    private data class TestShelf(val location: String, val cache: SimpleCache, val index: DefaultDownloadIndex)

    private fun shelf(name: String): TestShelf {
        val cache = SimpleCache(folders.newFolder(name), NoOpCacheEvictor(), database)
        cache.checkInitialization()
        return TestShelf(name,cache, DefaultDownloadIndex(database, name))
    }

    private fun save(shelf: TestShelf, requestId: String, key: String?, payload: ByteArray?, data: ByteArray,
        state: Int = Download.STATE_COMPLETED, uri: String = "$origin/api1/fileopus/${song.id}") {
        if (payload != null) seed(shelf, key ?: uri, payload, data)
        val request = DownloadRequest.Builder(requestId, Uri.parse(uri)).setCustomCacheKey(key).setData(data).build()
        shelf.index.putDownload(Download(request, state, 0L, 0L, 4L, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
    }

    private fun seed(shelf: TestShelf, key: String, payload: ByteArray, data: ByteArray?) {
        val hole = shelf.cache.startReadWrite(key, 0L, payload.size.toLong())
        try {
            val file = shelf.cache.startFile(key, 0L, payload.size.toLong())
            file.writeBytes(payload)
            shelf.cache.commitFile(file, payload.size.toLong())
            val metadata = ContentMetadataMutations()
            if (data != null) metadata.set(SONG_METADATA, data)
            ContentMetadataMutations.setContentLength(metadata, payload.size.toLong())
            shelf.cache.applyContentMetadataMutations(key, metadata)
        } finally { shelf.cache.releaseHoleSpan(hole) }
    }

    private fun records(shelf: TestShelf): List<Download> {
        val rows = ArrayList<Download>()
        shelf.index.getDownloads().use { cursor -> while (cursor.moveToNext()) rows += cursor.download }
        return rows
    }

    private fun bytes(shelf: TestShelf, key: String): ByteArray =
        requireNotNull(shelf.cache.getCachedSpans(key).single().file).readBytes()

    /** Every index row, key, metadata value and span file's name and bytes, as sorted text. */
    private fun snapshot(shelf: TestShelf): List<String> {
        val lines = records(shelf).mapTo(ArrayList()) { d ->
            "${d.request.id}|${d.state}|${d.request.customCacheKey}|${d.request.uri}|${d.request.data.contentToString()}"
        }
        shelf.cache.keys.sorted().forEach { key ->
            val metadata = shelf.cache.getContentMetadata(key)
            lines += "$key|${ContentMetadata.getContentLength(metadata)}|" +
                metadata.get(SONG_METADATA, null as ByteArray?)?.contentToString()
            shelf.cache.getCachedSpans(key).forEach { span ->
                lines += "${span.position}|${span.length}|${span.file?.name}|${span.file?.readBytes()?.contentToString()}"
            }
        }
        return lines.sorted()
    }
}
