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
    @Player.RepeatMode val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /**
     * Whether the Previous button does anything: it restarts the song, or goes to the one before when
     * the song has only just begun (Media3's `seekToPrevious`). [previous] stays "there is a song
     * before this one", which the swipes need: they show that song and never restart this one.
     */
    val previousOrRestart: Boolean = false)

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
 * Only a live controller can answer this. A rotation or a restored process brings back the overlay's
 * saved flag before the new controller has connected, so "no controller" means "not known yet",
 * never "nothing is playing". Closing on that would throw away the user's place. (Going to the
 * background no longer shows as "no controller" at all: `MainActivity.onStop` keeps the released one
 * published until its replacement connects.)
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
                player.repeatMode, player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
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
        // Ends with the connection: in the background the controller is released but still published
        // (MainActivity.onStop), and would answer 0 for the position on every tick. Its replacement is
        // a new key, which starts this again.
        while (player.isConnected) {
            state.position = player.currentPosition.coerceAtLeast(0)
            delay(POSITION_TICK_MS)
        }
    }
    return state
}
