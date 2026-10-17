@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.IOException
import java.util.concurrent.Executors
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
class StorageStartupNativeTest {
    @get:Rule val folders=TemporaryFolder()
    @Test fun actualCacheAndWorkersStayOwnedAfterLaterFailureAndOriginalBytesAreNotRepairedOrDeleted() {
        val root=folders.newFolder()
        val provider=StandaloneDatabaseProvider(RuntimeEnvironment.getApplication())
        val owner=StorageStartup<Any>()
        var cache:SimpleCache?=null
        val workers=Executors.newFixedThreadPool(2)
        val key="saved/startup-fixture"
        var opens=0
        try {
            assertThrows(IOException::class.java) { owner.open { opening ->
                opening.own { provider }
                val native=opening.own { SimpleCache(root,NoOpCacheEvictor(),provider) }.also { cache=it; opens++ }
                native.checkInitialization()
                val hole=requireNotNull(native.startReadWrite(key,0,4))
                try {
                    val file=native.startFile(key,0,4); file.writeBytes(byteArrayOf(2,4,6,8)); native.commitFile(file,4)
                } finally { native.releaseHoleSpan(hole) }
                native.applyContentMetadataMutations(key,ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
                opening.own { workers }
                throw IOException("dependent manager could not be constructed")
            } }
            assertEquals(3,owner.retained); assertTrue(SimpleCache.isCacheFolderLocked(root))
            repeat(3) { assertThrows(IOException::class.java) { owner.open { opens++; Any() } } }
            assertEquals(1,opens); assertFalse(workers.isShutdown)
            val native=requireNotNull(cache)
            assertTrue(native.isCached(key,0,4)); assertArrayEquals(byteArrayOf(2,4,6,8),native.getCachedSpans(key).first().file!!.readBytes())
        } finally {
            // Fixture teardown only. Production has no reset/cleanup without actual drain evidence.
            workers.shutdownNow(); cache?.release(); provider.close()
        }
    }
}
