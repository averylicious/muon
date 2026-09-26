package dev.avery.muon

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    override fun onCreate() {
        super.onCreate()
        // Keep Media3's notification/actions and foreground handling; only supply our app mark.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply {
            setSmallIcon(R.drawable.ic_notification)
        })
        val player = ExoPlayer.Builder(this)
            // A downloaded song plays its copy from the phone; everything else streams as before (#112).
            .setMediaSourceFactory(DefaultMediaSourceFactory(
                OfflineStore.playbackSource(this, OkHttpDataSource.Factory(Transport.client))))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        // A shuffle that starts from the chosen song and keeps Play next and Add to queue where they
        // were put (see QueueShuffleOrder). Turning shuffle on reshuffles from the playing song, so
        // everything else is still to come rather than wherever an old shuffle had left it.
        player.setShuffleOrder(QueueShuffleOrder())
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && player.mediaItemCount > 0)
                    player.setShuffleOrder(QueueShuffleOrder.startingWith(player.mediaItemCount, player.currentMediaItemIndex))
            }
        })
        // Shuffle and repeat as the user left them, restored before the session exists so no
        // controller sees the defaults, and saved whenever they change by any route — the app, the
        // notification or another controller — rather than at shutdown, which may never be reached.
        restoreAndPersistPlaybackModes(
            PlaybackModePreferences(getSharedPreferences(PlaybackModePreferences.FILE, MODE_PRIVATE)),
            object : PlaybackModeTarget {
                override fun restore(modes: PlaybackModes) {
                    player.shuffleModeEnabled = modes.shuffle
                    player.repeatMode = modes.repeat
                }
                override fun listen(onShuffle: (Boolean) -> Unit, onRepeat: (Int) -> Unit) {
                    player.addListener(object : Player.Listener {
                        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
                            onShuffle(shuffleModeEnabled)
                        override fun onRepeatModeChanged(repeatMode: Int) = onRepeat(repeatMode)
                    })
                }
            })
        session = MediaSession.Builder(this, player)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader(
                DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(), OkHttpDataSource.Factory(Transport.client))))
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return if (c.packageName == packageName || c.isTrusted)
                        super.onConnect(s, c) else MediaSession.ConnectionResult.reject()
                }
                override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo,
                    items: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                    // Only our UI supplies queue URLs. External system controllers keep transport controls.
                    if (c.packageName != packageName) return Futures.immediateFuture(mutableListOf())
                    return try {
                        items.forEach { item ->
                            val uri = requireNotNull(item.localConfiguration?.uri)
                            val endpoint = ServerEndpoint.parse("${uri.scheme}://${uri.encodedAuthority}")
                            require(uri.toString().startsWith(endpoint.origin + "/api1/file/"))
                            require(uri.path.orEmpty().matches(Regex("/api1/file/[0-9]+")))
                            require(uri.query == null && uri.fragment == null)
                        }
                        Futures.immediateFuture(items)
                    } catch (e: Exception) { Futures.immediateFailedFuture(e) }
                }
            }).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        session?.run { player.release(); release() }; session = null
        super.onDestroy()
    }
}
fun TauonTrack.mediaItem(endpoint: ServerEndpoint): MediaItem = MediaItem.Builder()
    .setMediaId("${endpoint.origin}/$id")
    .setUri(endpoint.url("/api1/file/$id"))
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(displayCredits(artist)).setAlbumTitle(album)
        .setAlbumArtist(displayCredits(albumArtist))
        // Queue display only. Seek/Now Playing must keep using the player's stream duration.
        .setDurationMs(durationMs.takeIf { it > 0 })
        .setArtworkUri(android.net.Uri.parse(endpoint.url("/api1/pic/medium/$id"))).build())
    .build()
