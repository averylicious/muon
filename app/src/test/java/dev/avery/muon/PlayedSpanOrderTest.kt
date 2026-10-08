@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheSpan
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlayedSpanOrderTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var context: Context
    private lateinit var database: StandaloneDatabaseProvider
    private val caches = ArrayList<SimpleCache>()
    private val orders = ArrayList<DiskPlayedSpanOrder>()
    @Before fun setup() {
        val root = folders.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getNoBackupFilesDir() = root
        }
        database = StandaloneDatabaseProvider(context)
    }
    @After fun close() {
        try { caches.asReversed().forEach { it.release() } }
        finally { try { orders.forEach { it.close() } } finally { database.close() } }
    }

    @Test fun diskOrderingMatchesOriginalTreeSetAcrossTouchesTiesAndExactNames() {
        val disk = order()
        val memory = MemoryPlayedSpanOrder()
        val spans = (0..1300).map { n -> CacheSpan("played:" + when (n % 4) {
            0 -> "\uD83D\uDE00"; 1 -> "\uE000"; 2 -> "\u0000\uD800"; else -> "a".repeat(1400)
        } + (n / 4), (n % 3) * 64L, 4, (n % 11).toLong(), File("fixture")) }
        spans.reversed().forEach { disk.add(it); memory.add(it) }
        assertEquals(memory.count, disk.count)
        val touched = spans[3]
        disk.remove(touched); memory.remove(touched)
        val next = CacheSpan(touched.key, touched.position, touched.length, 99, touched.file)
        disk.add(next); memory.add(next)
        val keep = spans[0].key
        val protected = spans[1].key
        while (true) {
            val expected = memory.oldest(keep) { it != protected }
            assertEquals(expected, disk.oldest(keep) { it != protected })
            if (expected == null) break
            for (span in spans + next) if (span.key == expected) { disk.remove(span); memory.remove(span) }
            assertEquals(memory.count, disk.count)
        }
    }

    @Test fun aNewProcessRebuildDoesNotTrustPreviousDerivedRows() {
        val file = folders.newFile()
        val old = DiskPlayedSpanOrder(file)
        old.add(CacheSpan("played:stale", 0, 4, 1, File("fixture")))
        assertEquals(1L, old.count)
        old.close()
        val fresh = DiskPlayedSpanOrder(file).also(orders::add)
        fresh.initialized()
        assertEquals(0L, fresh.count)
        assertNull(fresh.oldest(null) { true })
        fresh.add(CacheSpan("played:actual", 0, 4, 2, File("fixture")))
        assertEquals("played:actual", fresh.oldest(null) { true })
    }

    @Test fun realCacheEvictsAWholePlayedSongButNeverAClaimedKeyOrSavedDownload() {
        val claims = PlayedClaims(context)
        val order = order()
        val evictor = PlayedSongEvictor(64, claims::removable, order) {}
        val cache = cache(evictor)
        seed(cache, "played:old", 0, 4); seed(cache, "played:old", 8, 4)
        seed(cache, "played:claimed", 0, 4); seed(cache, "saved:explicit", 0, 8)
        // Unknown download-index ownership protects every played key, even over budget.
        evictor.resize(0)
        assertTrue(cache.isCached("played:old",0,4))
        val index = DefaultDownloadIndex(database, "played_order")
        val request = DownloadRequest.Builder("hidden/owner", Uri.parse("https://fixture.invalid/audio"))
            .setCustomCacheKey("played:claimed").build()
        index.putDownload(Download(request, Download.STATE_COMPLETED, 1, 2, 4,
            Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE))
        claims.read(index)
        assertTrue(claims.known)
        evictor.resize(4)
        assertFalse(cache.isCached("played:old",0,1))
        assertFalse(cache.isCached("played:old",8,1))
        assertTrue(cache.isCached("played:claimed",0,4))
        assertTrue(cache.isCached("saved:explicit",0,8))
        assertEquals(request,index.getDownload(request.id)?.request)
        assertEquals(1L,order.count)
        // Keep the resource being written even when its own size exceeds the setting.
        seed(cache,"played:writing",0,8)
        assertTrue(cache.isCached("played:writing",0,8))
        assertTrue(cache.isCached("played:claimed",0,4))
    }

    @Test fun failedDiskOrderingStopsEvictionAndPreservesOriginalCachedBytes() {
        val order = order()
        val evictor = PlayedSongEvictor(1, order = order) {}
        val cache = cache(evictor)
        seed(cache,"saved:original",0,8)
        seed(cache,"played:original",0,8)
        order.close() // Lost private derived authority, with the audio cache still alive.
        seed(cache,"played:new",0,8)
        evictor.resize(0)
        assertTrue(cache.isCached("played:original",0,8))
        assertTrue(cache.isCached("played:new",0,8))
        assertTrue(cache.isCached("saved:original",0,8))
    }

    private fun order() = DiskPlayedSpanOrder(folders.newFile()).also(orders::add)
    private fun cache(evictor: PlayedSongEvictor) =
        SimpleCache(folders.newFolder(),evictor,database).also { caches += it; it.checkInitialization() }
    private fun seed(cache: SimpleCache, key: String, position: Long, length: Int) {
        val hole = requireNotNull(cache.startReadWrite(key,position,length.toLong()))
        try {
            val file = cache.startFile(key,position,length.toLong())
            file.writeBytes(ByteArray(length) { 7 })
            cache.commitFile(file,length.toLong())
        } finally { cache.releaseHoleSpan(hole) }
    }
}
