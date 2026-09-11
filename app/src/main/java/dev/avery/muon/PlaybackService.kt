package dev.avery.muon

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
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
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Transport.client)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
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
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(android.net.Uri.parse(endpoint.url("/api1/pic/medium/$id"))).build())
    .build()
