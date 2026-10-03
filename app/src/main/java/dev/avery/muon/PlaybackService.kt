package dev.avery.muon

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    // Held here: SharedPreferences keeps its listeners only weakly.
    private var loudnessListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
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
            // Each song that starts playing is copied for offline listening (#112), behind playback.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null || OfflineStore.offline) return
                OfflineStore.copyPlayed(this@PlaybackService, mediaItem.mediaId, mediaItem.mediaMetadata.extras?.getByteArray(SONG_EXTRA))
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && player.mediaItemCount > 0)
                    player.setShuffleOrder(QueueShuffleOrder.startingWith(player.mediaItemCount, player.currentMediaItemIndex))
            }
        })
        // Volume normalization (#97): each song at its ReplayGain level, set as the player's volume.
        val loudnessPrefs = getSharedPreferences(ReplayGainSettings.FILE, MODE_PRIVATE)
        val loudness = ReplayGainSettings(loudnessPrefs)
        fun applyLoudness() { player.volume = loudness.volumeFor(player.currentMediaItem?.mediaId) }
        player.addListener(object : Player.Listener {
            // A song already heard starts at its level; one not yet heard plays unchanged until its
            // tags are read, rather than at the previous song's gain.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyLoudness()
            override fun onTracksChanged(tracks: Tracks) {
                val id = player.currentMediaItem?.mediaId
                val found = tracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                    .flatMap { group -> (0 until group.length).asSequence().filter(group::isTrackSelected)
                        .map { group.getTrackFormat(it) } }
                    .firstNotNullOfOrNull { trackLoudness(it.metadata) }
                if (id != null && found != null) loudness.remember(id, appliedGainDb(found))
                applyLoudness()
            }
        })
        loudnessListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ReplayGainSettings.KEY_ENABLED) { loudness.reload(); applyLoudness() }
        }.also(loudnessPrefs::registerOnSharedPreferenceChangeListener)
        applyLoudness()
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
            .setBitmapLoader(CacheBitmapLoader(notificationBitmapLoader(this,
                OkHttpDataSource.Factory(Transport.metadataClient))))
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(PlaybackSessionCallback()).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session
    override fun onDestroy() {
        loudnessListener?.let { getSharedPreferences(ReplayGainSettings.FILE, MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(it) }; loudnessListener = null
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
        .setArtworkUri(android.net.Uri.parse(endpoint.url("/api1/pic/medium/$id")))
        // The song's own record, so the service can keep a played copy the offline library can list.
        .setExtras(android.os.Bundle().apply { putByteArray(SONG_EXTRA, encodeSong(this@mediaItem)) }).build())
    .build().let(::queueOccurrence)
