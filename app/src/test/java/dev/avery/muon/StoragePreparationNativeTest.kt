@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import android.os.Looper
import androidx.media3.datasource.cache.SimpleCache
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StoragePreparationNativeTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val prepare=OfflineStore::class.java.declaredMethods.single { it.name=="prepareLegacy" }.apply { isAccessible=true }
    private fun open(owner:StorageStartup<Any>):Any=owner.open { prepare.invoke(OfflineStore,app,it) }
    /** Only this isolated fixture's unopened service/cache graph is drained. No production reset. */
    private fun cleanup(owner:StorageStartup<Any>) {
        val handles=StorageStartup::class.java.getDeclaredField("handles").apply { isAccessible=true }.get(owner) as Array<*>
        for(resource in handles.reversed()) when(resource) {
            is ExecutorService -> { resource.shutdown(); assertTrue(resource.awaitTermination(5,TimeUnit.SECONDS)) }
            is SimpleCache -> resource.release()
            is Closeable -> resource.close()
        }
    }
    @Test fun realLegacyPreparationRequiresWorkerAndDoesNotBindAnyManager() {
        assertSame(Looper.getMainLooper(),Looper.myLooper())
        val rejected=StorageStartup<Any>()
        val error=assertThrows(InvocationTargetException::class.java) { open(rejected) }
        assertTrue(error.cause is IllegalStateException); assertEquals(0,rejected.retained)
        val owner=StorageStartup<Any>(); val executor=Executors.newSingleThreadExecutor()
        try {
            val ready=executor.submit<Any> { open(owner) }.get(10,TimeUnit.SECONDS)
            val phone=ready.javaClass.getDeclaredField("phone").apply { isAccessible=true }.get(ready)
            val cache=phone.javaClass.getDeclaredField("cache").apply { isAccessible=true }.get(phone) as SimpleCache
            cache.checkInitialization(); assertTrue(SimpleCache.isCacheFolderLocked(File(app.filesDir,"downloads")))
            assertFalse(phone.javaClass.declaredFields.any { it.type==androidx.media3.exoplayer.offline.DownloadManager::class.java })
            assertNull(OfflineStore.current())
        } finally { cleanup(owner); executor.shutdownNow() }
    }
    @Test fun stagedPartitionSelectionRefusesBeforeAnyLegacyProviderCacheOrDirectoryCreation() {
        val prefs=app.getSharedPreferences("storage",Context.MODE_PRIVATE)
        prefs.edit().putString(StorageBackendSelection.KEY,"partition-v1").commit()
        val owner=StorageStartup<Any>(); val executor=Executors.newSingleThreadExecutor()
        val directory=File(app.filesDir,"downloads")
        try {
            val error=assertThrows(java.util.concurrent.ExecutionException::class.java) {
                executor.submit<Any> { open(owner) }.get(10,TimeUnit.SECONDS)
            }
            assertTrue(error.cause is InvocationTargetException)
            assertTrue(error.cause!!.cause is java.io.IOException)
            assertEquals(0,owner.retained); assertFalse(directory.exists())
            assertFalse(SimpleCache.isCacheFolderLocked(directory)); assertNull(OfflineStore.current())
        } finally { prefs.edit().clear().commit(); executor.shutdownNow() }
    }
}
