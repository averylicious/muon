package dev.avery.muon

import android.app.Notification
import android.content.Intent
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
 * progress notification, so leaving the app does not stop them. No scheduler. After process restart, unfinished saves and pending removals are kept stopped;
 * new saves use fresh entries instead of resuming unverified retained bytes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MuonDownloadService : DownloadService(DOWNLOAD_NOTIFICATION, DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_CHANNEL, R.string.downloads_channel, 0) {
    private val notifications by lazy { DownloadNotificationHelper(this, DOWNLOAD_CHANNEL) }

    override fun getDownloadManager(): DownloadManager = OfflineStore.get(this).phone.manager

    // #230: a command that could change downloads is refused while a move is in flight, before Media3 sees it.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super.onStartCommand(OfflineStore.admitCommand(this, intent, OfflineStore.get(this).phone), flags, startId)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notifications.buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)
}

private const val CARD_NOTIFICATION = 3

/**
 * Downloads to the SD card (#112): the same as [MuonDownloadService], for the card's own manager. Only
 * started while a card is in; created with none, it is given the phone's manager for the rest of the
 * process, and then refuses card commands rather than carry them out on the phone's downloads (#179). It
 * also refuses them while its card is unavailable.
 * That fallback is still resumed when the service is created, and Media3's own restarts still run.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MuonCardDownloadService : DownloadService(CARD_NOTIFICATION, DownloadService.DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    DOWNLOAD_CHANNEL, R.string.downloads_channel, 0) {
    private val notifications by lazy { DownloadNotificationHelper(this, DOWNLOAD_CHANNEL) }

    /** The manager this instance's commands reach, copied once Media3 has attached it. */
    private var bound: DownloadManager? = null

    // Media3 asks only when it makes this class's helper, then builds the helper with this manager and
    // reuses it for every later instance without asking again (DownloadService 596-611, Media3 1.11.0).
    override fun getDownloadManager(): DownloadManager =
        OfflineStore.get(this).let { (it.card ?: it.phone).manager }.also { helperManager = it }

    override fun onCreate() {
        super.onCreate()
        // Per instance: a live instance keeps its helper even if a later instance is given a new one.
        bound = helperManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // A command this binding may carry out still passes the move's admission (#230), as the phone's does.
        if (intent == null || action == null || action !in CHANGING_ACTIONS || isCardManager(bound))
            return super.onStartCommand(OfflineStore.admitCommand(this, intent, OfflineStore.current()?.card), flags, startId)
        // A copy that changes nothing, with the same extras, so a foreground start still shows its notification.
        return super.onStartCommand(Intent(intent).setAction(DownloadService.ACTION_INIT), flags, startId)
    }

    private fun isCardManager(manager: DownloadManager?): Boolean {
        val store = OfflineStore.current() ?: return false
        val card = store.card ?: return false
        // Also refused while the card is unavailable (#179 S1), as the senders already do. A snapshot: the
        // card can go right after this check.
        return manager != null && manager === card.manager && manager !== store.phone.manager && card.available()
    }

    private companion object {
        /** The commands that change a manager's downloads, pause state, stop reasons or requirements. */
        val CHANGING_ACTIONS = setOf(DownloadService.ACTION_ADD_DOWNLOAD, DownloadService.ACTION_REMOVE_DOWNLOAD,
            DownloadService.ACTION_REMOVE_ALL_DOWNLOADS, DownloadService.ACTION_RESUME_DOWNLOADS, DownloadService.ACTION_PAUSE_DOWNLOADS,
            DownloadService.ACTION_SET_STOP_REASON, DownloadService.ACTION_SET_REQUIREMENTS)

        /** The manager Media3 built this class's helper with; kept as long as its helper map, the process. */
        @Volatile var helperManager: DownloadManager? = null
    }

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        notifications.buildProgressNotification(this, R.drawable.ic_notification, null, null, downloads, notMetRequirements)
}
