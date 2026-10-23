package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
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
import java.io.IOException

/** #213 library mechanism control only; no production saved route or identity contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedCacheOnlyControlTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private val savedKey = "opaque-retained-copy-A"
    private val savedBytes = byteArrayOf(1, 2, 3, 4)
    // Deliberately different URI identity. No HTTP source or socket is created in these controls.
    private val liveUri = "http://127.0.0.1:7814/api1/file/99"

    @Before fun setUp() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder("saved"), NoOpCacheEvictor(), database)
        cache.checkInitialization()
    }

    @After fun tearDown() {
        try { cache.release() } finally { database.close() }
    }

    @Test fun exactSavedKeyReadsRetainedBytesDespiteADifferentLiveUri() {
        seed(savedBytes)
        val source = cacheOnly()
        try {
            assertEquals(4L, source.open(request(savedKey)))
            val read = ByteArray(4)
            assertEquals(4, source.read(read, 0, read.size))
            assertArrayEquals(savedBytes, read)
            assertEquals(-1, source.read(ByteArray(1), 0, 1))
        } finally { source.close() }
        assertArrayEquals(savedBytes, requireNotNull(cache.getCachedSpans(savedKey).single().file).readBytes())
    }

    @Test fun missingSavedKeyFailsInsteadOfFetchingTheLiveUriOrUsingAnotherCachedKey() {
        seed(savedBytes)
        val source = cacheOnly()
        try {
            try {
                source.open(request("missing-retained-copy"))
                fail("Missing saved bytes must not open the live URI or substitute another entry")
            } catch (_: IOException) { /* Actual PlaceholderDataSource rejects the cache miss. */ }
        } finally { source.close() }
        assertEquals(4L, cache.cacheSpace)
        assertArrayEquals(savedBytes, requireNotNull(cache.getCachedSpans(savedKey).single().file).readBytes())
    }

    @Test fun aSavedPrefixIsReadButTheMissingSuffixFailsWithoutFillingTheGap() {
        seed(savedBytes.copyOf(2))
        val source = cacheOnly()
        try {
            assertEquals(4L, source.open(request(savedKey)))
            val first = ByteArray(4)
            assertEquals(2, source.read(first, 0, first.size))
            assertArrayEquals(savedBytes.copyOf(2), first.copyOf(2))
            try {
                source.read(first, 0, first.size)
                fail("A missing suffix must fail, not produce bytes from a reused live ID")
            } catch (_: IOException) { /* No upstream exists to fill the retained-copy hole. */ }
        } finally { source.close() }
        assertEquals(2L, cache.cacheSpace)
        assertFalse(cache.isCached(savedKey, 0L, 4L))
        assertArrayEquals(savedBytes.copyOf(2), requireNotNull(cache.getCachedSpans(savedKey).single().file).readBytes())
    }

    private fun cacheOnly(): CacheDataSource = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null).createDataSource()

    private fun request(key: String): DataSpec = DataSpec.Builder().setUri(liveUri).setKey(key).build()

    private fun seed(payload: ByteArray) {
        val hole = cache.startReadWrite(savedKey, 0L, payload.size.toLong())
        try {
            val file = cache.startFile(savedKey, 0L, payload.size.toLong())
            file.writeBytes(payload)
            cache.commitFile(file, payload.size.toLong())
            cache.applyContentMetadataMutations(savedKey, ContentMetadataMutations().apply {
                ContentMetadataMutations.setContentLength(this, savedBytes.size.toLong())
            })
        } finally { cache.releaseHoleSpan(hole) }
    }
}
