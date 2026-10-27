package dev.avery.muon

import android.os.Process
import android.os.Bundle
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

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
    private val shufflePlayer: ExoPlayer? = null,
) : MediaSession.Callback {
    override fun onConnectAsync(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.ConnectionResult> {
        val result = when {
            // The UI owns content edits; Media3 publishes notification grants to platform controls.
            ownsQueue(session, controller) -> MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(REMOVE_QUEUE_ITEM).add(RESTORE_QUEUE_REMOVAL).build(),
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
            controller.uid == Process.myUid() || controller.isTrusted -> MediaSession.ConnectionResult.accept(
                SessionCommands.EMPTY, externalCommands,
            )
            else -> MediaSession.ConnectionResult.reject()
        }
        return Futures.immediateFuture(result)
    }

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        val action = customCommand.customAction
        if (action != REMOVE_QUEUE_ITEM.customAction && action != RESTORE_QUEUE_REMOVAL.customAction)
            return super.onCustomCommand(session, controller, customCommand, args)
        if (!ownsQueue(session, controller)) return Futures.immediateFuture(
            SessionResult(SessionError.ERROR_PERMISSION_DENIED))
        if (action == REMOVE_QUEUE_ITEM.customAction) {
            // Removal and the order Undo needs come from the actual player in this one operation, on
            // its thread; a controller's pending edits leave its own timeline unshuffled.
            val removal = QueueRemovalRequest.fromBundle(args) ?: return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_BAD_VALUE))
            val ticket = removal.applyTo(session.player) ?: return Futures.immediateFuture(
                SessionResult(SessionError.ERROR_INVALID_STATE))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, ticket))
        }
        val request = QueueRemovalTicket.fromBundle(args) ?: return Futures.immediateFuture(
            SessionResult(SessionError.ERROR_BAD_VALUE))
        val exo = shufflePlayer ?: (session.player as? ExoPlayer) ?: return Futures.immediateFuture(
            SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        if (!request.matches(session.player)) return Futures.immediateFuture(
            SessionResult(SessionError.ERROR_INVALID_STATE))
        // Reuse the ordinary URI, metadata-budget and asynchronous saved-copy admission gate.
        val admitted = onAddMediaItems(session, controller, mutableListOf(request.item))
        val handler = Handler(session.player.applicationLooper)
        return Futures.transform(admitted, { items ->
            val item = items?.singleOrNull()
            SessionResult(if (item != null && request.apply(session.player, exo, item))
                SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE)
        }, Executor { work ->
            // Guava propagates a rejection to the result; another exception would leave it pending.
            if (!handler.post(work)) throw RejectedExecutionException("The player's thread has stopped")
        })
    }

    override fun onAddMediaItems(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        // A successful empty list means replace the queue with nothing in default onSetMediaItems.
        // Fail instead, including legacy play/prepare-from requests delegated through that callback.
        if (!ownsQueue(session, controller)) return Futures.immediateFailedFuture(
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

    /** A notification controller runs under our UID, but its grants also describe platform controls.
     * Keep content edits with the UI, including after Media3 resolves System UI to that controller.
     * UID remains the identity check; the supported notification classifier only narrows our grant.
     */
    private fun ownsQueue(session: MediaSession, controller: MediaSession.ControllerInfo): Boolean =
        controller.uid == Process.myUid() && !session.isMediaNotificationController(controller)

    private companion object {
        // Service replacement must not bypass the pending-work bound: its old executor shuts down
        // gracefully, so cancelled/queued/running work can outlive that callback. Release only on
        // actual worker exit (or the production executor's known pre-submission rejection).
        private val savedAdmissionPending = java.util.concurrent.atomic.AtomicBoolean()
        // An allowlist also keeps future Media3 commands from expanding external capabilities.
        // Read access supplies system metadata/position; transport includes shuffle/repeat and
        // selecting an entry already in the queue. Volume/gain, configuration and queue edits stay
        // with Muon's UI. Raw track labels/codec initialization are unnecessary for transport controls
        // and can make remote track bundles oversized (#738); GET_METADATA still supplies song text.
        // System media-volume buttons do not need these session player-volume setters.
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
        ).build()
    }
}
