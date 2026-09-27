package dev.avery.muon

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now Playing, as the overlay that grows out of the mini player. The host closes it when the queue
 * is emptied — pausing and reaching the end both keep the current track — so there is no empty
 * state here.
 */
@Composable
internal fun NowPlayingOverlay(p: PlaybackUi, position: () -> Long, revision: () -> Int,
    player: MediaController?, dismiss: PlayerSheet?, collapse: () -> Unit, queue: () -> Unit, lyrics: () -> Unit) {
    if (p.item == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        // Taken here, where this scope's own height is in reach, rather than down inside the
        // column and a density block, where the outer receiver is no longer resolvable.
        val tall = maxHeight
        val dragHeight = with(LocalDensity.current) { tall.toPx() }
        val away by rememberUpdatedState(collapse)
        if (sidewaysLayout(maxWidth.value, maxHeight.value)) {
            // Landscape: stacked, the controls fell off the bottom of a short screen. The cover takes
            // the height on the start side and everything else sits beside it, scrolling only if
            // large text still needs more room than the height gives.
            val side = minOf(maxHeight - 24.dp, maxWidth * 0.45f).coerceAtLeast(0.dp)
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                SwipeableArtwork(p, player, revision, Modifier.size(side))
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val narrow = maxWidth < 360.dp
                    // At least the pane's height, so the controls are centred beside the cover
                    // whenever they fit, and scroll from the top when they do not.
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        // Lyrics and Queue join the top bar: along the bottom they fell below the fold,
                        // where only a scroll with nothing hinting at it would find them.
                        PlayerBar(dismiss, dragHeight, collapse, actions = {
                            IconButton(onClick = lyrics, modifier = Modifier.semantics { contentDescription = "Lyrics" }) {
                                MuonIcon("lyrics", Modifier.size(20.dp))
                            }
                            IconButton(onClick = queue, modifier = Modifier.semantics { contentDescription = "Queue" }) {
                                MuonIcon("queue", Modifier.size(20.dp))
                            }
                        }) { away() }
                        PlayerTitles(p)
                        PlayerControls(p, position, player, narrow, queue, lyrics, actions = false)
                    }
                }
            }
        } else {
            val narrow = maxWidth < 360.dp
            // Keep the compact portrait design, but allow every control to remain reachable in
            // split screen, large text, or when an error needs additional space.
            val scrollable = maxHeight < 600.dp * fontScale || narrow || p.error != null
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize()
                .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerBar(dismiss, dragHeight, collapse) { away() }
                Box((if (scrollable) Modifier.height(160.dp) else Modifier.weight(1f)).fillMaxWidth(),
                    contentAlignment = Alignment.Center) {
                    SwipeableArtwork(p, player, revision, Modifier.widthIn(max = 400.dp).aspectRatio(1f))
                }
                PlayerTitles(p)
                PlayerControls(p, position, player, narrow, queue, lyrics)
            }
        }
    }
}

/**
 * A visible way out, so a gesture is never the only exit (#40). The bar is also the one place the
 * player can be dragged away from, which leaves the content below it scrolling, the artwork swiping
 * and the sliders seeking as they did.
 */
@Composable
private fun PlayerBar(dismiss: PlayerSheet?, dragHeight: Float, collapse: () -> Unit,
    actions: (@Composable RowScope.() -> Unit)? = null, away: () -> Unit) {
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
        if (actions != null) Row(Modifier.align(Alignment.CenterEnd), content = actions)
    }
}

@Composable
private fun PlayerTitles(p: PlaybackUi) {
    val item = p.item ?: return
    Column(Modifier.fillMaxWidth()) {
        // Medium, as the app's other titles are (#16 QA).
        Text(item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(item.mediaMetadata.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Left out for singles, whose album is usually the title again.
        albumLine(item.mediaMetadata.title?.toString(), item.mediaMetadata.albumTitle?.toString())?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Everything under the titles: seek, transport, volume, then Lyrics and Queue unless [actions] is false. */
@Composable
private fun ColumnScope.PlayerControls(p: PlaybackUi, position: () -> Long, player: MediaController?,
    narrow: Boolean, queue: () -> Unit, lyrics: () -> Unit, actions: Boolean = true) {
    val item = p.item ?: return
    if (p.error != null) ErrorCard(p.error, "Retry stream", modifier = Modifier) { player?.prepare(); player?.play() }
    if (p.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
    SeekControls(item.mediaId, position, p.duration, p.seekable, player)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        if (!narrow) ShuffleControl(p, player)
        Control("previous", "Previous track", p.previous && player != null) { player?.seekToPreviousMediaItem() }
        // Round while paused, a rounded square while playing (motion pass 2).
        FilledIconButton(onClick = { if (p.playing) player?.pause() else player?.play() }, enabled = player != null,
            shape = playButtonShape(p.playing, 72.dp),
            modifier = Modifier.size(72.dp).semantics { contentDescription = if (p.playing) "Pause" else "Play" }) {
            Crossfade(p.playing, animationSpec = motionShort(), label = "play/pause") { playing ->
                MuonIcon(if (playing) "pause" else "play", Modifier.size(32.dp))
            }
        }
        Control("next", "Next track", p.next && player != null) { player?.seekToNextMediaItem() }
        if (!narrow) RepeatControl(p, player)
    }
    if (narrow) Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ShuffleControl(p, player)
        RepeatControl(p, player)
    }
    VolumeRow()
    // Wrapping preserves readable labels at large font/display sizes. Lyrics sits at the start
    // and Queue at the end, as in the mockup; on a narrow width they wrap rather than clip.
    if (actions) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = lyrics) {
            MuonIcon("lyrics", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Lyrics")
        }
        TextButton(onClick = queue) {
            MuonIcon("queue", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Queue")
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
/** How long a committed card takes to leave, and how long it waits off-screen for the player. */
private const val STACK_DEAL_MS = 180
private const val STACK_RETURN_MS = 700L
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
    // Each cover is its own card rather than a window onto a strip (#65): the playing one is dealt
    // off the top of the stack, tilting as it goes, and the neighbour it reveals grows into place.
    val card = RoundedCornerShape(24.dp)
    Box(modifier
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
                    if (action != SwipeAction.None && live != null) {
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        // The top card is dealt off the side it was going first, and only then does the
                        // player move on. The player answers within a frame or two, and its event
                        // rebuilds this group with the revealed cover on top; skipping first took the
                        // card away mid-flight, so a slow swipe's card vanished instead of leaving.
                        val away = size.width * 1.3f * (if (action == SwipeAction.Next) -1 else 1)
                        moving?.cancel()
                        moving = scope.launch {
                            offset.animateTo(away, tween(STACK_DEAL_MS))
                            // The release's checks again: a track that ended by itself while the card
                            // was leaving is not skipped as well.
                            if (controller.value === live && swipeTargetUnchanged(now, swipeTarget(live, revision()))) {
                                if (action == SwipeAction.Next) live.seekToNextMediaItem() else live.seekToPreviousMediaItem()
                            }
                            // Only if the player never answers does the card come back.
                            delay(STACK_RETURN_MS)
                            offset.animateTo(0f, SwipeSpring)
                        }
                    } else recentre()
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
        // Beneath: whichever neighbour the top card is moving away from, drawn only while it shows.
        fun Modifier.under(towardsNext: Boolean) = graphicsLayer {
            val fraction = offset.value / size.width.coerceAtLeast(1f)
            val showing = if (towardsNext) fraction < 0f else fraction > 0f
            val pose = stackPose(fraction)
            alpha = if (showing) pose.underAlpha else 0f
            scaleX = pose.underScale; scaleY = pose.underScale
            shape = card; clip = true
        }
        if (sides.hasPrevious) Artwork(sides.previous, Modifier.matchParentSize().under(towardsNext = false))
        if (sides.hasNext) Artwork(sides.next, Modifier.matchParentSize().under(towardsNext = true))
        Artwork(p.item?.mediaMetadata?.artworkUri?.toString(),
            Modifier.matchParentSize().graphicsLayer {
                val pose = stackPose(offset.value / size.width.coerceAtLeast(1f))
                translationX = offset.value
                rotationZ = pose.cardTilt
                scaleX = pose.cardScale; scaleY = pose.cardScale
                // Lifted off the stack while it moves, flat again at rest.
                shadowElevation = (kotlin.math.abs(offset.value) / 12f).coerceAtMost(12.dp.toPx())
                shape = card; clip = true
            })
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
