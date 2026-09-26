package dev.avery.muon

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler

private const val DOWNLOAD_CHANNEL = "downloads"
private const val DOWNLOAD_NOTIFICATION = 2

/**
 * Runs downloads for offline listening (#112) in the foreground while any are in progress, with a
 * progress notification, so leaving the app does not stop them. No scheduler: a download that could
 * not finish carries on the next time Muon asks for downloads.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MuonDownloadService : DownloadService(DOWNLOAD_NOTIFICATION, DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_CHANNEL, R.string.downloads_channel, 0) {
    private val notifications by lazy { DownloadNotificationHelper(this, DOWNLOAD_CHANNEL) }

    override fun getDownloadManager(): DownloadManager = OfflineStore.get(this).manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notifications.buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)
}
