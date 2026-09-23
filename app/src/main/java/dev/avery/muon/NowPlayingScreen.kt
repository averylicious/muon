package dev.avery.muon

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Now Playing, as the overlay that grows out of the mini player. The host closes it when the queue
 * is emptied — pausing and reaching the end both keep the current track — so there is no empty
 * state here.
 */
@Composable
internal fun NowPlayingOverlay(p: PlaybackUi, position: () -> Long, revision: () -> Int,
    player: MediaController?, dismiss: PlayerSheet?, collapse: () -> Unit, lyrics: () -> Unit) {
    if (p.item == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val narrow = maxWidth < 360.dp
        // Keep the compact portrait design, but allow every control to remain reachable in
        // landscape, split screen, large text, or when an error needs additional space.
        val scrollable = maxHeight < 600.dp * fontScale || narrow || p.error != null
        val scroll = rememberScrollState()
        // Taken here, where this scope's own height is in reach, rather than down inside the
        // column and a density block, where the outer receiver is no longer resolvable.
        val tall = maxHeight
        val dragHeight = with(LocalDensity.current) { tall.toPx() }
        Column(Modifier.fillMaxSize()
            .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A visible way out, so a gesture is never the only exit (#40). The bar is also the
            // one place the player can be dragged away from, which leaves the content below it
            // scrolling, the artwork swiping and the sliders seeking as they did.
            val away by rememberUpdatedState(collapse)
            Box(Modifier.fillMaxWidth().dismissDrag(dismiss, dragHeight) { away() },
                contentAlignment = Alignment.Center) {
                // A decorative grabber marks the existing draggable bar without adding another
                // control or shrinking its touch area. Center it on the player, not the space
                // left beside the collapse button, and use the active theme's surface contrast.
                // Painted rather than a Surface, which would add an empty node for a screen reader
                // to land on and a touch target of its own.
                Box(Modifier.size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
                FilledTonalIconButton(onClick = collapse,
                    modifier = Modifier.align(Alignment.CenterStart)
                        .semantics { contentDescription = "Collapse the player" }) {
                    MuonIcon("collapse", Modifier.size(20.dp))
                }
            }
            Box((if (scrollable) Modifier.height(160.dp) else Modifier.weight(1f)).fillMaxWidth(),
                contentAlignment = Alignment.Center) {
                SwipeableArtwork(p, player, revision, Modifier.widthIn(max = 400.dp).aspectRatio(1f))
            }
            Column(Modifier.fillMaxWidth()) {
                Text(p.item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.item.mediaMetadata.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.item.mediaMetadata.albumTitle?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p.error != null) ErrorCard(p.error, "Retry stream") { player?.prepare(); player?.play() }
            if (p.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            SeekControls(p.item.mediaId, position, p.duration, p.seekable, player)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                if (!narrow) ShuffleControl(p, player)
                Control("previous", "Previous track", p.previous && player != null) { player?.seekToPreviousMediaItem() }
                FilledIconButton(onClick = { if (p.playing) player?.pause() else player?.play() }, enabled = player != null,
                    modifier = Modifier.size(72.dp).semantics { contentDescription = if (p.playing) "Pause" else "Play" }) {
                    Crossfade(p.playing, animationSpec = motionShort(), label = "play/pause") { playing ->
                        MuonIcon(if (playing) "pause" else "play", Modifier.size(32.dp))
                    }
                }
                Control("next", "Next track", p.next && player != null) { player?.seekToNextMediaItem() }
                if (!narrow) RepeatControl(p, player)
            }
            if (narrow) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ShuffleControl(p, player)
                RepeatControl(p, player)
            }
            VolumeRow()
            // Wrapping preserves readable labels at large font/display sizes. Queue joins this row
            // when #47 builds it; there is no point offering a button that leads nowhere.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = lyrics) {
                    MuonIcon("lyrics", Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Lyrics")
                }
            }
        }
    }
}

/**
 * Volume lives in the player now rather than behind a dialog. The slider keeps its floating
 * percentage, and the speakers mark the ends the way the mockup does.
 */
@Composable
private fun VolumeRow() {
    val volume = rememberMediaVolumeController()
    val state = volume.state
    // The bubble needs room past each end of the track. Spending it between the speakers and the
    // track pushed the speakers out to the screen edges and left a gap either side; spending it
    // outside the row instead keeps them beside the track, and the bubble floats over them, which
    // it can do because it rides above the track line rather than on it. Only the part the
    // speakers do not already provide is added, so at ordinary text sizes there is none.
    val overhang = (volumeBubbleMargin() - (VolumeIconSize + VolumeIconGap)).coerceAtLeast(0.dp)
    Column(Modifier.fillMaxWidth()) {
        // Aligned to the track the slider publishes, not to the middle of the control: the space
        // it reserves above for the percentage bubble would otherwise push the speakers up.
        Row(Modifier.fillMaxWidth().padding(horizontal = overhang)) {
            MuonIcon("volume-low", Modifier.size(VolumeIconSize).alignBy { it.measuredHeight / 2 })
            MediaVolumeSlider(state, volume::setVolume,
                Modifier.weight(1f).padding(horizontal = VolumeIconGap).alignBy(VolumeTrackCenter))
            MuonIcon("volume", Modifier.size(VolumeIconSize).alignBy { it.measuredHeight / 2 })
        }
        if (state.fixed) Text("Volume is fixed by this device.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        volume.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** The speakers marking each end of the volume track, and their distance from it. */
private val VolumeIconSize = 18.dp
private val VolumeIconGap = 8.dp

/** A deliberate drag, not a flick: below this the artwork springs back. */
private val SwipeMinimum = 48.dp
private val SwipeSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow)

/**
 * The player's own answer to "what is playing, what is either side of it, and would you move".
 *
 * The neighbour indices come from the player rather than from arithmetic, so shuffle is already
 * accounted for and Repeat One is ignored exactly as it is for the buttons.
 */
private fun swipeTarget(player: MediaController, revision: Int): SwipeTarget {
    // Everything below the timeline command is meaningless without it, including the count and
    // both neighbour getters, so nothing is read until it is known to be available.
    val timeline = player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)
    val count = if (timeline) player.mediaItemCount else 0
    val next = if (timeline) player.nextMediaItemIndex.takeIf { it in 0 until count } else null
    val previous = if (timeline) player.previousMediaItemIndex.takeIf { it in 0 until count } else null
    return SwipeTarget(
        revision = revision,
        mediaId = if (player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM))
            player.currentMediaItem?.mediaId else null,
        index = if (timeline) player.currentMediaItemIndex else C.INDEX_UNSET,
        queueSize = count,
        nextIndex = next ?: C.INDEX_UNSET,
        previousIndex = previous ?: C.INDEX_UNSET,
        // A direction is available only if there is a track there *and* the player will move to it.
        canNext = next != null && player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
        canPrevious = previous != null
            && player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
    )
}

/** The covers either side of the current one. A null cover still means the track is there. */
@Immutable
private data class Neighbours(val previous: String?, val hasPrevious: Boolean,
    val next: String?, val hasNext: Boolean)

/**
 * Covers for the directions the target says are available, so nothing previews a destination the
 * player would refuse to move to. Read once per track or queue change, never while a finger moves.
 */
private fun neighbours(player: MediaController?, target: SwipeTarget?): Neighbours {
    if (player == null || target == null) return Neighbours(null, false, null, false)
    fun art(index: Int): String? = player.getMediaItemAt(index).mediaMetadata.artworkUri?.toString()
    return Neighbours(
        previous = if (target.canPrevious) art(target.previousIndex) else null,
        hasPrevious = target.canPrevious,
        next = if (target.canNext) art(target.nextIndex) else null,
        hasNext = target.canNext,
    )
}

/**
 * Artwork that follows a horizontal drag and changes track when the drag commits.
 *
 * The Previous and Next buttons remain the reliable way to do this; the gesture is the shortcut.
 *
 * The covers either side slide in with it, so what is revealed is the track that a release would
 * actually select — the player's own next and previous, shuffle included.
 *
 * The drag distance lives inside the pointer handler, and all three covers read the offset in the
 * draw phase through [graphicsLayer] rather than in composition, so moving them does not recompose
 * the player.
 *
 * A gesture belongs to the track, the neighbours and the controller it began on. All of them are
 * captured at the start and must still match at the end: a track ending by itself mid-drag would
 * otherwise make the release skip its replacement, and a queue replaced with one of the same
 * length, or shuffle being toggled, would commit to a cover the user was never shown.
 */
@Composable
private fun SwipeableArtwork(p: PlaybackUi, player: MediaController?, revision: () -> Int,
    modifier: Modifier = Modifier) {
    // One gesture per controller and event revision. When the player moves on, the gesture below
    // is disposed and rebuilt: its pointer coroutine is cancelled, its settling job dies with its
    // scope, and it returns centred with fresh covers — even if the finger never moved. A finger
    // still down cannot resume, because a new detector waits for a new press.
    val shown = revision()
    key(player, shown) { ArtworkGesture(p, player, shown, revision, modifier) }
}

/**
 * The gesture as it exists for one snapshot of the player. [shown] is the revision these covers
 * were chosen from; [revision] is read again live, to catch an event that has not yet reached
 * composition.
 */
@Composable
private fun ArtworkGesture(p: PlaybackUi, player: MediaController?, shown: Int,
    revision: () -> Int, modifier: Modifier) {
    val offset = remember { Animatable(0f) }
    val controller = rememberUpdatedState(player)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // The snapshot these covers were chosen from. A gesture may only act on this exact state.
    val displayed = remember { player?.let { swipeTarget(it, shown) } }
    // The covers load through the usual bounded Artwork cache, once per group.
    val sides = remember { neighbours(player, displayed) }
    Box(modifier
        // The neighbours wait outside the viewport until a drag pulls them in.
        .clip(RoundedCornerShape(24.dp))
        .pointerInput(Unit) {
            val minimum = SwipeMinimum.toPx()
            // Kept here rather than in state: a drag must not recompose anything to move pixels.
            var drag = 0f
            var began: MediaController? = null
            var startedOn: SwipeTarget? = null
            // One job owns the offset. Cancelling it before each move stops a settling animation
            // from resuming on top of the gesture that interrupted it.
            var moving: Job? = null
            fun move(to: Float) { moving?.cancel(); moving = scope.launch { offset.snapTo(to) } }
            fun recentre() { moving?.cancel(); moving = scope.launch { offset.animateTo(0f, SwipeSpring) } }
            fun release() { drag = 0f; began = null; startedOn = null }
            detectHorizontalDragGestures(
                onDragStart = {
                    moving?.cancel()
                    drag = 0f
                    // A player event can land before the composition that would rebuild this
                    // group, so a drag starting in that window would be acting on covers that are
                    // already stale. Start only if the player still agrees with what is drawn.
                    val live = controller.value
                    val now = live?.let { swipeTarget(it, revision()) }
                    val agrees = live === player && displayed != null && now == displayed
                    began = if (agrees) live else null
                    startedOn = if (agrees) displayed else null
                },
                onDragCancel = {
                    release()
                    recentre()
                },
                onDragEnd = {
                    val live = controller.value
                    // Same controller, same track, same place in the same queue, or nothing.
                    val now = live?.let { swipeTarget(it, revision()) }
                    val valid = live != null && live === began && swipeTargetUnchanged(startedOn, now)
                    val action = if (!valid || now == null) SwipeAction.None
                        else swipeAction(drag, size.width, minimum, now.canNext, now.canPrevious)
                    // Cleared before dispatching, so a second gesture cannot repeat this one.
                    release()
                    if (action != SwipeAction.None) {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        if (action == SwipeAction.Next) live?.seekToNextMediaItem()
                        else live?.seekToPreviousMediaItem()
                    }
                    recentre()
                },
                onHorizontalDrag = { change, delta ->
                    change.consume()
                    val start = startedOn
                    // The covers on screen belong to the moment the drag began. If the player has
                    // moved on since — another track, another queue, shuffle, a withdrawn command,
                    // a different controller — the picture is already wrong, so the gesture ends
                    // here rather than swapping covers under a finger that is still down.
                    if (start == null || controller.value !== began || revision() != start.revision) {
                        release()
                        recentre()
                        return@detectHorizontalDragGestures
                    }
                    drag += delta
                    // Resistance follows what the gesture began on, so the artwork cannot promise a
                    // move the commit check is about to refuse. Only the drawing is bounded; the raw
                    // distance still decides what a release does.
                    move(clampedSwipeOffset(swipeOffset(drag, start.canNext, start.canPrevious), size.width))
                },
            )
        }
    ) {
        if (sides.hasPrevious) Artwork(sides.previous,
            Modifier.matchParentSize().graphicsLayer { translationX = offset.value - size.width })
        if (sides.hasNext) Artwork(sides.next,
            Modifier.matchParentSize().graphicsLayer { translationX = offset.value + size.width })
        Artwork(p.item?.mediaMetadata?.artworkUri?.toString(),
            Modifier.matchParentSize().graphicsLayer { translationX = offset.value })
    }
}

@Composable
private fun ShuffleControl(p: PlaybackUi, player: MediaController?) {
    ToggleControl("shuffle", "Shuffle", if (p.shuffle) "On" else "Off", p.shuffle, player != null) {
        player?.shuffleModeEnabled = !p.shuffle
    }
}

@Composable
private fun RepeatControl(p: PlaybackUi, player: MediaController?) {
    ToggleControl(repeatModeIcon(p.repeatMode), "Repeat", repeatModeName(p.repeatMode),
        p.repeatMode != Player.REPEAT_MODE_OFF, player != null) {
        player?.repeatMode = nextRepeatMode(p.repeatMode)
    }
}

@Composable
private fun SeekControls(mediaId: String, position: () -> Long, duration: Long, seekable: Boolean, player: MediaController?) {
    var scrub by remember(mediaId) { mutableStateOf<Float?>(null) }
    val range = duration.coerceAtLeast(1).toFloat()
    val elapsed = scrub ?: position().toFloat().coerceIn(0f, range)
    Column {
        Slider(value = elapsed, onValueChange = { scrub = it }, valueRange = 0f..range,
            onValueChangeFinished = { scrub?.let { player?.seekTo(it.toLong()) }; scrub = null },
            enabled = seekable && player != null, modifier = Modifier.semantics { contentDescription = "Seek position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsed.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}
