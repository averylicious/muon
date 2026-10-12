@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.DownloadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Delay Media3's synchronous helper attachment until the ONE process startup finishes. No intent,
 * request payload or mutation is queued while storage opens. A foreground request is acknowledged
 * immediately with a preparation notification, then only a harmless INIT reaches Media3. An old
 * service intent never gains fresh save/removal authority after process death.
 *
 * Pinned Media3 1.11.0 DownloadService.onCreate attaches/resumes its cached manager synchronously;
 * onDestroy requires that helper. Call those methods only for this instance's completed attachment.
 */
abstract class InitializedDownloadService(private val notificationId:Int):DownloadService(notificationId,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,"downloads",R.string.downloads_channel,0) {
    private val initializationScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var attached=false
    private var destroyed=false
    private var pendingStartId:Int?=null
    private var foreground=false

    final override fun onCreate() {
        if(storageReady()) { attach(); return }
        initializationScope.launch {
            try {
                awaitStorage()
                if(destroyed) return@launch
                attach()
                // No early command is replayed. Only scalars needed for platform lifecycle survive.
                pendingStartId?.let { id ->
                    superOnStart(Intent(this@InitializedDownloadService,this@InitializedDownloadService.javaClass)
                        .setAction(ACTION_INIT).putExtra(KEY_FOREGROUND,foreground),0,id)
                }
                pendingStartId=null
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) {
                if(!destroyed) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
    }
    private fun attach() {
        check(!attached && !destroyed)
        super.onCreate()
        attached=true
        onStorageAttached()
    }
    protected open fun storageReady():Boolean=OfflineStore.current()!=null
    protected open suspend fun awaitStorage() { OfflineStore.await(this) }
    protected open fun onStorageAttached() {}
    private fun superOnStart(intent:Intent?,flags:Int,startId:Int)=super.onStartCommand(intent,flags,startId)
    protected abstract fun handleReadyCommand(intent:Intent?,flags:Int,startId:Int):Int
    /** Concrete command policies call this only after attachment; bypassing them is INIT-only above. */
    protected fun startAdmitted(intent:Intent?,flags:Int,startId:Int):Int {
        check(attached && !destroyed)
        return superOnStart(intent,flags,startId)
    }
    final override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(attached && !destroyed) return handleReadyCommand(intent,flags,startId)
        if(destroyed) return START_NOT_STICKY
        pendingStartId=startId
        if(intent!=null) {
            OfflineStore.refusedMoveCommand(intent)
            OfflineStore.refusedSaveCommand(intent)
            OfflineStore.refusedRemovalCommand(intent,OfflineStore.current()?.let { ready ->
                if(this is MuonCardDownloadService) ready.card else ready.phone
            })
            // Media3's private RESTART implies foreground even without KEY_FOREGROUND.
            foreground=foreground || intent.getBooleanExtra(KEY_FOREGROUND,false) ||
                intent.action=="androidx.media3.exoplayer.downloadService.action.RESTART"
        }
        if(foreground) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
                "downloads",getString(R.string.downloads_channel),NotificationManager.IMPORTANCE_LOW))
            startForeground(notificationId,NotificationCompat.Builder(this,"downloads")
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name))
                .setContentText("Opening saved copies…").setOngoing(true).build())
        }
        return START_NOT_STICKY
    }
    final override fun onTaskRemoved(rootIntent:Intent?) {
        if(attached) super.onTaskRemoved(rootIntent)
    }
    final override fun onDestroy() {
        destroyed=true
        initializationScope.cancel()
        if(attached) super.onDestroy() else stopForeground(STOP_FOREGROUND_REMOVE)
    }
}
