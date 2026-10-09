@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SavedCacheReaderTest {
    @get:Rule val folder=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=mutableListOf<SimpleCache>()
    private fun cache()=SimpleCache(folder.newFolder(),NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private val first=byteArrayOf(1,2,3,4); private val second=byteArrayOf(5,6,7,8)
    private fun seed(cache:SimpleCache) {
        for((position,bytes) in listOf(0L to first,4L to second)) {
            val hole=requireNotNull(cache.startReadWrite("saved",position,4))
            try { val file=cache.startFile("saved",position,4); file.writeBytes(bytes); cache.commitFile(file,4) }
            finally { cache.releaseHoleSpan(hole) }
        }
        cache.applyContentMetadataMutations("saved",ContentMetadataMutations.setContentLength(ContentMetadataMutations(),8))
    }
    private fun spec()=DataSpec.Builder().setUri(Uri.parse("muon-saved:fragment")).setKey("saved").setLength(8).build()
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }
    @Test fun normalFragmentChangesReadExactBytesAndKnownCloseAllowsReopen() {
        val native=cache(); seed(native); val reader=savedCacheReader(native)
        repeat(2) {
            assertEquals(8L,reader.open(spec())); val bytes=ByteArray(8); var count=0
            while(count<8) { val read=reader.read(bytes,count,8-count); assertTrue(read>0); count+=read }
            assertArrayEquals(first+second,bytes); assertEquals(-1,reader.read(ByteArray(1),0,1)); reader.close()
        }
    }
    @Test fun unknownCloseBetweenNativeFragmentsCannotBeClearedByLaterOuterClose() {
        val native=cache(); seed(native); val actual=mutableListOf<DataSource>(); var closes=0
        val files=DataSource.Factory {
            val raw=FileDataSource.Factory().createDataSource().also(actual::add)
            object:DataSource by raw { override fun close() { closes++; throw IOException("Injected fragment close") } }
        }
        try {
            val reader=savedCacheReader(native,files); reader.open(spec())
            assertEquals(4,reader.read(ByteArray(8),0,8))
            assertThrows(IOException::class.java) { reader.read(ByteArray(8),0,8) }
            assertThrows(IOException::class.java) { reader.close() }
            assertThrows(IOException::class.java) { reader.open(spec()) }
            assertEquals(1,closes); assertEquals(2,native.getCachedSpans("saved").size)
        } finally { actual.forEach { it.close() } }
    }
    @Test fun productionComparisonRetainsReaderAfterMidFragmentCloseFailureAndNeverReportsMatch() {
        val source=cache(); val target=cache(); seed(source); seed(target)
        val actual=mutableListOf<DataSource>(); var closes=0; var unknown=0; val owner=MoveByteComparison()
        val files=DataSource.Factory {
            val raw=FileDataSource.Factory().createDataSource().also(actual::add)
            object:DataSource by raw { override fun close() { closes++; throw IOException("Injected mid-read close") } }
        }
        try {
            assertThrows(IOException::class.java) {
                owner.compare(spec(),LegacySavedAudio(source,files).source,LegacySavedAudio(target).source,{true},{unknown++})
            }
            assertEquals(1,unknown); assertEquals(1,owner.active); assertEquals(1,closes)
            assertEquals(8L,source.cacheSpace); assertEquals(8L,target.cacheSpace)
        } finally { actual.forEach { it.close() } }
    }
}
