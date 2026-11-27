package dev.avery.muon

import androidx.compose.runtime.*
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

/**
 * Everything about playback that changes only on a player event. The moving position is kept out
 * of this value deliberately: it is compared for equality on every event, so screens that do not
 * show a clock are not recomposed while a track plays.
 */
@Immutable
internal data class PlaybackUi(val item: MediaItem? = null, val playing: Boolean = false,
    val duration: Long = 0, val seekable: Boolean = false,
    val buffering: Boolean = false, val error: String? = null, val previous: Boolean = false,
    val next: Boolean = false, val shuffle: Boolean = false,
    @Player.RepeatMode val repeatMode: Int = Player.REPEAT_MODE_OFF,
    // MediaItem.equals ignores Bundle extras; carry the occurrence UUID explicitly so switching
    // between duplicate occurrences of the same song recomposes list highlights (#783).
    val occurrenceKey: String? = null)

/** Playback state split so that the ticking position invalidates only the widgets that draw it. */
@Stable
internal class PlaybackState {
    var ui by mutableStateOf(PlaybackUi())
    var position by mutableLongStateOf(0L)
    /**
     * Bumped when the queue, its order or what the player will accept changes.
     *
     * A gesture that shows the next cover has to be invalidated by more than the current track's
     * identity: replacing a queue with one of the same length, or toggling shuffle, changes which
     * track is next without changing the media ID, the index or the count. The position ticker
     * deliberately does not touch this.
     */
    var revision by mutableIntStateOf(0)
}

/**
 * Whether an open Now Playing overlay should close itself.
 *
 * Only a live controller can answer this. `MainActivity` releases the controller in `onStop`, and a
 * rotation restores the overlay's saved flag before the new controller has connected, so "no
 * controller" means "not known yet", never "nothing is playing". Closing on that would throw away
 * the user's place every time the app went to the background.
 */
internal fun overlayShouldClose(controllerAttached: Boolean, hasCurrentItem: Boolean): Boolean =
    controllerAttached && !hasCurrentItem

/**
 * Formats a player error for the UI. Media3's Binder serialization turns non-string-constructible
 * causes (such as `ParserException` and `Loader.UnexpectedLoaderException`) into `RemoteException`,
 * so parsing error codes and loader-unexpected messages map to plain copy here as well as in
 * [friendlyError].
 */
internal fun playbackErrorMessage(error: PlaybackException): String {
    val detail = when {
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> UNREADABLE_AUDIO_FILE
        error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED &&
            error.cause?.message?.startsWith("Unexpected ") == true -> UNREADABLE_AUDIO_FILE
        else -> error.cause?.let(::friendlyError) ?: error.message
    }
    return "${error.errorCodeName}: $detail"
}

@Composable
internal fun rememberPlayback(player: MediaController?): PlaybackState {
    val state = remember { PlaybackState() }
    DisposableEffect(player) {
        fun update() {
            val item = player?.currentMediaItem
            state.ui = if (player == null) PlaybackUi() else PlaybackUi(item, player.isPlaying,
                player.duration.coerceAtLeast(0),
                player.isCurrentMediaItemSeekable, player.playbackState == Player.STATE_BUFFERING,
                player.playerError?.let(::playbackErrorMessage),
                player.hasPreviousMediaItem(), player.hasNextMediaItem(), player.shuffleModeEnabled,
                player.repeatMode, item?.let(::queueOccurrenceKey))
            state.position = player?.currentPosition?.coerceAtLeast(0) ?: 0L
        }
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                update()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) {
                    state.revision++
                }
            }
        }
        player?.addListener(listener); update()
        onDispose { player?.removeListener(listener) }
    }
    val playing = state.ui.playing
    LaunchedEffect(player, playing) {
        if (player == null || !playing) return@LaunchedEffect
        while (true) {
            state.position = player.currentPosition.coerceAtLeast(0)
            delay(POSITION_TICK_MS)
        }
    }
    return state
}
