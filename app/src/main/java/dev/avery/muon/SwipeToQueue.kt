package dev.avery.muon

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Queues a song: next after the playing one, or at the end. Provided by the app, null where there is no player. */
internal val LocalQueueSong = compositionLocalOf<((TauonTrack, next: Boolean) -> Unit)?> { null }

/** How far across the row a swipe must go, as a fraction of its width, before letting go queues the song. */
internal const val SWIPE_QUEUE_AT = 0.28f

/** How far the row can be pulled at most, so it never leaves the screen. */
internal const val SWIPE_QUEUE_MAX = 0.45f

/** What letting go at [offset] of a row [width] wide does: null for nothing, true for Play next, false for Add to queue. */
internal fun swipeQueueAction(offset: Float, width: Float): Boolean? = when {
    width <= 0f || abs(offset) < width * SWIPE_QUEUE_AT -> null
    else -> offset > 0f
}

/** The row follows the finger, harder to pull past the point where letting go acts. */
internal fun swipeQueueOffset(offset: Float, delta: Float, width: Float): Float {
    val at = width * SWIPE_QUEUE_AT
    val resisted = if (abs(offset) >= at && (offset > 0f) == (delta > 0f)) delta * 0.35f else delta
    return (offset + resisted).coerceIn(-width * SWIPE_QUEUE_MAX, width * SWIPE_QUEUE_MAX)
}

/**
 * The Canary experiment's swipe on a song row: towards the end for Play next, towards the start for
 * Add to queue. The row slides over its action, which lights up with a tick as the swipe passes
 * [SWIPE_QUEUE_AT]; letting go past it queues the song (with the usual Undo) and the row springs back.
 * TalkBack reaches both through the row's long-press song actions, as before. Nothing changes when
 * the song cannot be played or there is no player.
 */
@Composable
internal fun SwipeToQueue(track: TauonTrack, enabled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val queue = LocalQueueSong.current
    if (queue == null || !enabled) { Box(modifier) { content() }; return }
    var width by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    val next by remember { derivedStateOf { offset > 0f } }
    val armed by remember { derivedStateOf { swipeQueueAction(offset, width) != null } }
    val colors = MaterialTheme.colorScheme
    val ground by animateColorAsState(if (armed) colors.primaryContainer else colors.surfaceContainerHigh,
        motionShort(), label = "swipe action")
    val content2 = if (armed) colors.onPrimaryContainer else colors.onSurfaceVariant
    val drag = rememberDraggableState { delta ->
        val was = swipeQueueAction(offset, width) != null
        offset = swipeQueueOffset(offset, delta, width)
        val now = swipeQueueAction(offset, width) != null
        // Compose has no deactivate feedback, so only arming ticks; backing off is silent.
        if (now && !was) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    Box(modifier.onSizeChanged { width = it.width.toFloat() }
        .draggable(drag, Orientation.Horizontal, onDragStopped = {
            swipeQueueAction(offset, width)?.let { queue(track, it) }
            animate(offset, 0f, animationSpec = motionSpatial()) { value, _ -> offset = value }
        })) {
        // Shown only while the row is moved, behind it, on the side it uncovers.
        if (offset != 0f) Row(Modifier.matchParentSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
            .background(ground).padding(horizontal = 20.dp),
            horizontalArrangement = if (next) Arrangement.Start else Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides content2) {
                MuonIcon(if (next) "play-next" else "add-queue")
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }) { content() }
    }
}
