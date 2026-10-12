@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.app.Notification
import android.content.Intent
import android.os.Looper
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Actual Media3 helper/lifecycle, with a controlled readiness boundary only. No phone or network. */
class PendingStorageService:InitializedDownloadService(12) {
    lateinit var readiness:CompletableDeferred<Unit>
    lateinit var manager:DownloadManager
    var bound=0; var commands=0
    override fun storageReady()=false
    override suspend fun awaitStorage() { readiness.await() }
    override fun onStorageAttached() { bound++ }
    override fun getDownloadManager():DownloadManager=manager
    override fun getScheduler():Scheduler?=null
    override fun getForegroundNotification(downloads:MutableList<Download>,notMetRequirements:Int):Notification=
        DownloadNotificationHelper(this,"downloads").buildProgressNotification(this,R.drawable.ic_notification,null,null,downloads,notMetRequirements)
    override fun handleReadyCommand(intent:Intent?,flags:Int,startId:Int):Int {
        commands++; return startAdmitted(intent,flags,startId)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class InitializedDownloadServiceTest {
    @get:Rule val folders=TemporaryFolder()
    private val app get()=RuntimeEnvironment.getApplication()
    private inner class Fixture:java.io.Closeable {
        val database=StandaloneDatabaseProvider(app)
        val cache=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),database)
        val manager=DownloadManager(app,database,cache,androidx.media3.datasource.DataSource.Factory { ByteArrayDataSource(byteArrayOf(1,2)) },java.util.concurrent.Executor { it.run() })
        val readiness=CompletableDeferred<Unit>()
        val controller=Robolectric.buildService(PendingStorageService::class.java)
        val service=controller.get().also { it.manager=manager; it.readiness=readiness }
        init { DownloadService.clearDownloadManagerHelpers(); controller.create() }
        fun settle() {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
            while(!manager.isInitialized || !manager.isIdle) {
                shadowOf(Looper.getMainLooper()).idle(); check(System.nanoTime()<end); Thread.sleep(5)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        override fun close() {
            controller.destroy(); DownloadService.clearDownloadManagerHelpers(); manager.release(); cache.release(); database.close()
        }
    }
    @Test fun coldForegroundAddShowsPreparationThenOnlyInitAndNeverReplaysPayload() {
        Fixture().use { f ->
            val request=DownloadRequest.Builder("early",android.net.Uri.parse("http://127.0.0.1/unused")).build()
            f.service.onStartCommand(DownloadService.buildAddDownloadIntent(app,PendingStorageService::class.java,request,true),0,1)
            assertNotNull(shadowOf(f.service).lastForegroundNotification)
            assertEquals(0,f.service.bound); assertEquals(0,f.service.commands)
            f.readiness.complete(Unit); shadowOf(Looper.getMainLooper()).idle(); f.settle()
            assertEquals(1,f.service.bound); assertEquals(0,f.service.commands)
            assertNull(f.manager.downloadIndex.getDownload(request.id))
            // Subsequent harmless commands use the exact already-attached helper.
            f.service.onStartCommand(Intent(app,PendingStorageService::class.java).setAction(DownloadService.ACTION_INIT),0,2)
            assertEquals(1,f.service.commands); assertEquals(1,f.service.bound)
        }
    }
    @Test fun privateRestartKeepsForegroundObligationWhilePreparationIsPending() {
        Fixture().use { f ->
            f.service.onStartCommand(Intent(app,PendingStorageService::class.java)
                .setAction("androidx.media3.exoplayer.downloadService.action.RESTART"),0,1)
            assertNotNull(shadowOf(f.service).lastForegroundNotification)
            f.readiness.complete(Unit); shadowOf(Looper.getMainLooper()).idle(); f.settle()
            assertEquals(1,f.service.bound); assertTrue(f.manager.currentDownloads.isEmpty())
        }
    }
    @Test fun destroyingPendingServiceCannotAttachLaterOrCancelProcessReadiness() {
        val f=Fixture()
        try {
            f.controller.destroy()
            assertFalse(f.readiness.isCancelled)
            f.readiness.complete(Unit); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,f.service.bound)
        } finally { DownloadService.clearDownloadManagerHelpers(); f.manager.release(); f.cache.release(); f.database.close() }
    }
    @Test fun failedPreparationStopsServiceWithoutCallingMedia3DestroyWithMissingHelper() {
        Fixture().use { f ->
            f.service.onStartCommand(Intent(app,PendingStorageService::class.java).setAction(DownloadService.ACTION_INIT),0,1)
            f.readiness.completeExceptionally(java.io.IOException("Unavailable card"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(f.service).isStoppedBySelf)
            assertEquals(0,f.service.bound); assertEquals(0,f.service.commands)
        }
    }
}
