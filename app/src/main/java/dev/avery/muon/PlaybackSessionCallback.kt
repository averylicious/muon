package dev.avery.muon

import android.os.Process
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor

/**
 * The app owns the queue; trusted system controllers operate the existing queue only. A saved copy
 * (#213) is admitted only from Muon itself, by its exact handle, and only once [admitSaved] has found the
 * row or played copy it names, which is checked on [background] since it reads an index.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PlaybackSessionCallback(
    private val admitSaved: (SavedRef) -> Boolean = { false },
    private val background: Executor = Executor { it.run() },
    private val timing: SavedStartupTiming = SavedStartupTiming.DISABLED,
) : MediaSession.Callback {
    // Cancellation does not free a task that is still queued/running on the admission worker.
    private val savedAdmissionPending = java.util.concurrent.atomic.AtomicBoolean()
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
            require(playbackInputFits(mediaItems)) { "That queue has too many items or too much metadata" }
            val items = mediaItems.toMutableList()
            val saved = LinkedHashSet<SavedRef>()
            items.forEach { item ->
                val uri = requireNotNull(item.localConfiguration?.uri)
                if (uri.scheme == SAVED_SCHEME) {
                    // Exactly as Muon writes it, and the same handle as the item's own ID: no other spelling.
                    val ref = requireNotNull(SavedRef.parse(uri.toString())) { "Not a saved copy Muon made" }
                    require(item.mediaId == ref.handle)
                    saved += ref
                    return@forEach
                }
                val endpoint = ServerEndpoint.parse("${uri.scheme}://${uri.encodedAuthority}")
                require(uri.toString().startsWith(endpoint.origin + "/api1/file/"))
                require(uri.path.orEmpty().matches(Regex("/api1/file/[0-9]+")))
                require(uri.query == null && uri.fragment == null)
            }
            if (saved.isEmpty()) return Futures.immediateFuture(items)
            check(savedAdmissionPending.compareAndSet(false, true)) {
                "Muon is checking another saved queue. Retry after it finishes"
            }
            val admitted = SettableFuture.create<MutableList<MediaItem>>()
            val measured = timing.begin(SavedStartupTiming.Phase.ADMISSION)
            try {
                background.execute {
                    var outcome = SavedStartupTiming.Outcome.CANCELLED
                    try {
                        if (admitted.isCancelled) return@execute
                        for (ref in saved) {
                            if (admitted.isCancelled) return@execute
                            require(admitSaved(ref)) { "That saved copy isn't here any more" }
                        }
                        admitted.set(items)
                        outcome = SavedStartupTiming.Outcome.OK
                    } catch (e: Exception) { outcome = SavedStartupTiming.Outcome.FAILED; admitted.setException(e) }
                    finally { timing.end(measured, outcome); savedAdmissionPending.set(false) }
                }
            } catch (e: Exception) {
                timing.end(measured, SavedStartupTiming.Outcome.FAILED)
                savedAdmissionPending.set(false)
                admitted.setException(e)
            }
            admitted
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
