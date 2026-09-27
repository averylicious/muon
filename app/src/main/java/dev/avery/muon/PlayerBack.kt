package dev.avery.muon

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How far the open player shrinks and drifts while the system Back gesture previews closing it.
 * Material shrinks a surface by a fixed distance rather than a fixed fraction, so the preview reads
 * the same on a small phone as on a tablet.
 */
internal val PLAYER_BACK_SHRINK: Dp = 48.dp
internal val PLAYER_BACK_SLIDE: Dp = 24.dp
internal val PLAYER_BACK_CORNER: Dp = 28.dp

/**
 * One scale for both axes, measured against the width, because the artwork is square: shrinking the
 * two axes by different distances would stretch it. A surface narrower than the shrink distance
 * stops at nothing rather than turning inside out, and an unmeasured surface is left alone.
 */
internal fun playerBackScale(progress: Float, width: Float, shrink: Float): Float {
    if (width.isNaN() || width <= 0f) return 1f
    return 1f - minOf(shrink, width) * progress.coerceIn(0f, 1f) / width
}

/**
 * Follows the finger, as the platform does: a swipe from the left edge pushes the player right. A
 * button or hardware Back has no edge, and so nothing to follow.
 */
internal fun playerBackSlide(progress: Float, edge: Int, slide: Float): Float {
    val direction = when (edge) {
        BackEventCompat.EDGE_LEFT -> 1f
        BackEventCompat.EDGE_RIGHT -> -1f
        else -> 0f
    }
    return direction * slide * progress.coerceIn(0f, 1f)
}

/** How far a Back gesture over the player has come, and which edge it started from. */
internal class PlayerBackPreview {
    val shown = Animatable(0f)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    /** The animation putting the player back after a cancelled gesture, while it is still running. */
    var restore: Job? = null
}

/**
 * Registers the system Back gesture for the open player and reports its progress for drawing.
 *
 * Remember this inside the overlay's own transition, so the gesture state is created when the
 * player appears and discarded once it has gone rather than outliving it.
 *
 * [back] is the app's whole Back action rather than "close the player", and is read when the
 * gesture is let go rather than when it began: the handler passes the lambda it holds at the start
 * of a gesture to a coroutine that keeps it, so capturing it would commit a decision the app has
 * since moved on from. The caller decides whether a gesture whose target has gone acts at all.
 */
@Composable
internal fun rememberPlayerBackPreview(enabled: Boolean, back: () -> Unit): PlayerBackPreview {
    // Cancelling a gesture cancels the handler's own coroutine, so the animation that puts the
    // player back cannot run inside it.
    val scope = rememberCoroutineScope()
    val preview = remember { PlayerBackPreview() }
    val current by rememberUpdatedState(back)
    // Reopening the player before its closing animation ends reuses this composition, which would
    // otherwise still be holding the finished gesture's shrink. Nothing resets on the way out,
    // because the player should leave at the size the gesture left it.
    LaunchedEffect(enabled) { if (enabled) preview.shown.snapTo(0f) }
    PredictiveBackHandler(enabled) { gesture ->
        // A restore launched by the previous gesture can still be queued when this one starts, and
        // would then pull the preview back to rest under the finger. This gesture owns it now.
        preview.restore?.cancel()
        try {
            // Collected even when there is nothing to preview: a Back button completes the flow
            // immediately, with no events, and the handler requires it to be collected either way.
            gesture.collect {
                preview.edge = it.swipeEdge
                preview.shown.snapTo(PredictiveBackEasing.transform(it.progress.coerceIn(0f, 1f)))
            }
            current()
        } catch (cancelled: CancellationException) {
            preview.restore = scope.launch { preview.shown.animateTo(0f, motionSpatialFull()) }
        }
    }
    return preview
}

/**
 * Shrinks and drifts the whole surface, the way the platform treats a window it is about to
 * dismiss, so the tab underneath shows around it. Read in the draw phase, so following a finger
 * redraws the player without recomposing it.
 */
internal fun Modifier.playerBackPreview(preview: PlayerBackPreview?): Modifier =
    if (preview == null) this else graphicsLayer {
        val progress = preview.shown.value
        if (progress <= 0f) return@graphicsLayer
        val scale = playerBackScale(progress, size.width, PLAYER_BACK_SHRINK.toPx())
        scaleX = scale
        scaleY = scale
        translationX = playerBackSlide(progress, preview.edge, PLAYER_BACK_SLIDE.toPx())
        shape = RoundedCornerShape(PLAYER_BACK_CORNER * progress)
        clip = true
    }
