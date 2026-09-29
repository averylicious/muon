package dev.avery.muon

import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** The app owns the queue; trusted system controllers operate the existing queue only. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSessionCallback : MediaSession.Callback {
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val result = when {
            // Includes Media3's in-process notification controller. Package names are not identity.
            controller.uid == Process.myUid() -> MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
            controller.isTrusted -> MediaSession.ConnectionResult.accept(
                SessionCommands.EMPTY, externalCommands,
            )
            else -> MediaSession.ConnectionResult.reject()
        }
        return Futures.immediateFuture(result)
    }

    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        // A successful empty list means replace the queue with nothing in default onSetMediaItems.
        // Fail instead, including legacy play/prepare-from requests delegated through that callback.
        if (controller.uid != Process.myUid()) return Futures.immediateFailedFuture(
            UnsupportedOperationException("Only Muon can supply queue items"),
        )
        return try {
            mediaItems.forEach { item ->
                val uri = requireNotNull(item.localConfiguration?.uri)
                val endpoint = ServerEndpoint.parse("${uri.scheme}://${uri.encodedAuthority}")
                require(uri.toString().startsWith(endpoint.origin + "/api1/file/"))
                require(uri.path.orEmpty().matches(Regex("/api1/file/[0-9]+")))
                require(uri.query == null && uri.fragment == null)
            }
            Futures.immediateFuture(mediaItems)
        } catch (e: Exception) { Futures.immediateFailedFuture(e) }
    }

    private companion object {
        // An allowlist also keeps future Media3 commands from expanding external capabilities.
        // Read access supplies system metadata/position; transport includes shuffle/repeat and
        // selecting an entry already in the queue. Volume/gain, configuration and queue edits stay
        // with Muon. System media-volume buttons do not need these session player-volume setters.
        val externalCommands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_GET_TRACKS,
        ).build()
    }
}
