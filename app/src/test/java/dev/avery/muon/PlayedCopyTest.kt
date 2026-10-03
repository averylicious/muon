package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlayedCopyTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private val caches = mutableListOf<SimpleCache>()
    private val key = playedKey("http://192.168.1.10:7814/1")

    @Before fun setUp() { database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    @After fun tearDown() {
        try { caches.asReversed().forEach { it.release() } }
        finally { database.close() }
    }

    @Test fun normalPlayedCopyRemainsReadable() {
        val cache = open(512)
        val payload = ByteArray(256) { it.toByte() }
        copyPlayedWithinLimit(source(cache, payload), spec(key)) { 512 }
        assertTrue(cache.isCached(key, 0, 256))
        assertEquals(256L, cache.cacheSpace)
        assertArrayEquals(payload, cache.getCachedSpans(key).first().file!!.readBytes())
    }

    @Test fun declaredOversizeIsRejectedWithoutReadingOrDeletingDownloads() {
        val cache = open(128)
        seed(cache, "explicit-download", ByteArray(64) { 7 })
        var read = 0
        val raw = ByteArrayDataSource(ByteArray(256))
        val upstream = object : DataSource by raw {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                raw.read(buffer, offset, length).also { if (it > 0) read += it }
        }
        reject { copyPlayedWithinLimit(source(cache, upstream), spec(key)) { 128 } }
        assertEquals(0, read)
        assertFalse(cache.isCached(key, 0, 1))
        assertTrue(cache.isCached("explicit-download", 0, 64))
        assertEquals(64L, cache.cacheSpace)
    }

    @Test fun unknownLengthIsBoundedAndPartialPlayedSpansAreRemoved() {
        val budget = CacheWriter.DEFAULT_BUFFER_SIZE_BYTES.toLong()
        val cache = open(budget)
        var read = 0L
        val raw = ByteArrayDataSource(ByteArray((budget * 4).toInt()))
        val upstream = object : DataSource by raw {
            override fun open(dataSpec: DataSpec): Long {
                raw.open(dataSpec)
                return C.LENGTH_UNSET.toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                raw.read(buffer, offset, length).also { if (it > 0) read += it }
        }
        reject { copyPlayedWithinLimit(source(cache, upstream), spec(key)) { budget } }
        assertTrue(read > budget)
        assertTrue(read <= budget + CacheWriter.DEFAULT_BUFFER_SIZE_BYTES)
        assertFalse(cache.isCached(key, 0, 1))
        assertEquals(0L, cache.cacheSpace)
        // Rejection closed/released the writer's hole lock: Retry can write this same key.
        copyPlayedWithinLimit(source(cache, ByteArray(64)), spec(key)) { budget }
        assertTrue(cache.isCached(key, 0, 64))
    }

    @Test fun loweredBudgetDuringCopyRejectsAndCleansTheCurrentResource() {
        var budget = 512_000L
        val cache = open(budget)
        val raw = ByteArrayDataSource(ByteArray(256_000))
        val upstream = object : DataSource by raw {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                raw.read(buffer, offset, length).also { if (it > 0) budget = 1L }
        }
        reject { copyPlayedWithinLimit(source(cache, upstream), spec(key)) { budget } }
        assertEquals(0L, cache.cacheSpace)
    }

    @Test fun startupTrimsOldOversizedPlayedCopyButPreservesExplicitDownload() {
        val folder = folders.newFolder()
        val cache = open(128, folder)
        seed(cache, "explicit-download", ByteArray(64) { 7 })
        seed(cache, key, ByteArray(256)) // Models the old protected-key oversize outcome.
        assertTrue(cache.isCached(key, 0, 256))
        cache.release()
        val reopened = open(128, folder)
        assertFalse(reopened.isCached(key, 0, 1))
        assertTrue(reopened.isCached("explicit-download", 0, 64))
        assertEquals(64L, reopened.cacheSpace)
    }

    private fun open(limit: Long, folder: java.io.File = folders.newFolder()): SimpleCache =
        SimpleCache(folder, PlayedSongEvictor(limit) { }, database).also { caches += it; it.checkInitialization() }

    private fun spec(key: String) = DataSpec.Builder().setUri("https://fixture.invalid/song").setKey(key).build()
    private fun source(cache: SimpleCache, bytes: ByteArray) = source(cache, ByteArrayDataSource(bytes))
    private fun source(cache: SimpleCache, upstream: DataSource) = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory { upstream }.createDataSourceForDownloading()

    private fun seed(cache: SimpleCache, key: String, bytes: ByteArray) {
        val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
        try {
            val file = cache.startFile(key, 0, bytes.size.toLong())
            file.writeBytes(bytes)
            cache.commitFile(file, bytes.size.toLong())
        } finally { cache.releaseHoleSpan(hole) }
    }

    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected oversized played copy rejection") }
        catch (_: PlayedCopyTooLarge) { }
    }
}
