package dev.avery.muon

import androidx.compose.runtime.*
import androidx.media3.common.MediaItem
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
    @Player.RepeatMode val repeatMode: Int = Player.REPEAT_MODE_OFF)

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

@Composable
internal fun rememberPlayback(player: MediaController?): PlaybackState {
    val state = remember { PlaybackState() }
    DisposableEffect(player) {
        fun update() {
            state.ui = if (player == null) PlaybackUi() else PlaybackUi(player.currentMediaItem, player.isPlaying,
                player.duration.coerceAtLeast(0),
                player.isCurrentMediaItemSeekable, player.playbackState == Player.STATE_BUFFERING,
                player.playerError?.let { "${it.errorCodeName}: ${it.cause?.let(::friendlyError) ?: it.message}" },
                player.hasPreviousMediaItem(), player.hasNextMediaItem(), player.shuffleModeEnabled,
                player.repeatMode)
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
