package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
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
        // And the new save's own row, as after an earlier partial move: same ID and key, so a merge is harmless.
        put(target, "saved/b", "saved/b")
        assertFalse(movable(legacy, rows(source), rows(target)))
        assertTrue(movable(fresh, rows(source), rows(target)))
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
