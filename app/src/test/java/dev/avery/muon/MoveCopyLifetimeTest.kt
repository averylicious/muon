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
class MoveCopyLifetimeTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val natives=mutableListOf<SimpleCache>()
    private fun cache()=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database).also { natives+=it; it.checkInitialization() }
    private val bytes=byteArrayOf(1,2,3,4,5,6,7,8)
    private fun source():SimpleCache=cache().also { c ->
        val hole=requireNotNull(c.startReadWrite("saved",0,8))
        try { val file=c.startFile("saved",0,8);file.writeBytes(bytes);c.commitFile(file,8) }
        finally { c.releaseHoleSpan(hole) }
        c.applyContentMetadataMutations("saved",ContentMetadataMutations.setContentLength(ContentMetadataMutations(),8))
    }
    private fun spec()=DataSpec.Builder().setUri(Uri.parse("muon-saved:copy")).setKey("saved").build()
    @After fun close() { try { natives.asReversed().forEach { it.release() } } finally { database.close() } }
    @Test fun quietlyFailedActualSourceCloseRetainsOwnerAndRefusesNextCopyWithoutRetry() {
        val source=source(); val target=cache(); val owner=MoveCopyLifetime()
        val actual=mutableListOf<DataSource>();var closes=0;var notices=0
        val files=DataSource.Factory {
            val raw=FileDataSource.Factory().createDataSource().also(actual::add)
            object:DataSource by raw { override fun close() { closes++; throw IOException("Actual source stays open") } }
        }
        try {
            assertThrows(IOException::class.java) {
                owner.copy(spec(),LegacySavedAudio(source,files).source,target,MoveFileOutputs.Real,{true},{notices++})
            }
            assertTrue(owner.retained);assertEquals(1,closes);assertEquals(1,notices)
            assertEquals(8L,source.cacheSpace);assertEquals(8L,target.cacheSpace)
            assertThrows(IOException::class.java) {
                owner.copy(spec(),LegacySavedAudio(source).source,target,MoveFileOutputs.Real,{true},{notices++})
            }
            assertEquals(1,closes);assertEquals(1,notices)
        } finally { actual.forEach { it.close() } }
    }
    @Test fun failedTargetFileFactoryClosesExistingSourceAndReleasesKnownOwnerForRetry() {
        val source=source();val target=cache();val owner=MoveCopyLifetime();var closes=0
        val factory=DataSource.Factory {
            val raw=LegacySavedAudio(source).source.createDataSource()
            object:DataSource by raw { override fun close() { closes++;raw.close() } }
        }
        assertThrows(IOException::class.java) {
            owner.copy(spec(),factory,target,MoveFileOutputs.Real,{true},{fail("Known cleanup")},
                DataSource.Factory { throw IOException("Injected target factory refusal") })
        }
        assertEquals(1,closes);assertFalse(owner.retained);assertEquals(0L,target.cacheSpace)
        owner.copy(spec(),LegacySavedAudio(source).source,target,MoveFileOutputs.Real,{true},{fail("Healthy cleanup")})
        assertFalse(owner.retained);assertEquals(8L,source.cacheSpace);assertEquals(8L,target.cacheSpace)
    }
}
