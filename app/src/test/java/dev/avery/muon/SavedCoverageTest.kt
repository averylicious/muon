@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
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
import java.util.NavigableSet
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedCoverageTest {
    @get:Rule val folders = TemporaryFolder()
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    @Before fun setup() {
        database = StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        cache = SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database)
        cache.checkInitialization()
    }
    @After fun close() { try { cache.release() } finally { database.close() } }

    @Test fun scalarCoverageMatchesTheOldSpanSnapshotForAllNativeStates() {
        length("metadata-only",10)
        seed("full",0,4); seed("full",4,6); length("full",10)
        seed("hole",0,4); seed("hole",6,4); length("hole",10)
        seed("unknown",8,6)
        seed("zero-length",0,4); length("zero-length",0)
        seed("beyond",0,4); seed("beyond",12,4); length("beyond",4)
        for (key in listOf("missing","metadata-only","full","hole","unknown","zero-length","beyond")) {
            val spans = cache.getCachedSpans(key)
            val bytes = spans.sumOf { it.length }
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            val expected = when {
                spans.isEmpty() -> SavedCoverage.Missing
                length <= 0 -> SavedCoverage.UnknownLength
                cache.isCached(key,0,length) -> SavedCoverage.Full
                else -> SavedCoverage.Partial
            }
            assertEquals("$key",expected to bytes,savedCoverage(withoutSnapshots(),key))
        }
        assertEquals(SavedCoverage.Full to 8L,savedCoverage(withoutSnapshots(),"beyond"))
    }

    @Test fun manyFragmentsAreCountedWithoutAskingForTheFullSpanSet() {
        repeat(400) { seed("fragmented",it * 8L,4) }
        length("fragmented",3200)
        assertEquals(SavedCoverage.Partial to 1600L,savedCoverage(withoutSnapshots(),"fragmented"))
        assertEquals(400,cache.getCachedSpans("fragmented").size)
        assertEquals(1600L,cache.cacheSpace)
    }

    @Test fun scalarQueryFailureDoesNotReturnAFalseMissingOrCompleteCopy() {
        seed("original",0,4); length("original",4)
        val bad = object : Cache by cache {
            override fun getCachedBytes(key: String, position: Long, length: Long): Long =
                throw IOException("Scalar fixture failure")
        }
        assertThrows(IOException::class.java) { savedCoverage(bad,"original") }
        assertTrue(cache.isCached("original",0,4))
        assertEquals(SavedCoverage.Full to 4L,savedCoverage(cache,"original"))
    }

    private fun withoutSnapshots(): Cache = object : Cache by cache {
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> =
            throw AssertionError("Coverage must not clone all span references")
    }
    private fun length(key: String, length: Long) = cache.applyContentMetadataMutations(key,
        ContentMetadataMutations.setContentLength(ContentMetadataMutations(),length))
    private fun seed(key: String, position: Long, length: Int) {
        val hole = requireNotNull(cache.startReadWrite(key,position,length.toLong()))
        try {
            val file = cache.startFile(key,position,length.toLong())
            file.writeBytes(ByteArray(length) { 7 }); cache.commitFile(file,length.toLong())
        } finally { cache.releaseHoleSpan(hole) }
    }
}
