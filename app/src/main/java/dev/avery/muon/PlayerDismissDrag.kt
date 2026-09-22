package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Dragging the player's top bar downwards puts it away.
 *
 * Only the bar drags. The content below it keeps its own vertical scrolling, the artwork keeps its
 * horizontal swipe, and the sliders and buttons keep their own gestures, so this deliberately does
 * not take over the whole surface. Growing it to the whole player means a nested-scroll design,
 * which is not this slice.
 */
internal val PLAYER_DISMISS_DROP: Dp = 96.dp

/**
 * How far the surface is drawn below its resting place, for a cumulative [drag] that is positive
 * downwards. It follows the finger exactly, because the surface is on its way out rather than
 * being held back, and never rises above where it sits or falls past the screen it is leaving.
 */
internal fun playerDismissOffset(drag: Float, height: Float): Float =
    drag.coerceIn(0f, maxOf(height, 0f))

/** Whether letting go here puts the player away, judged on the finger's own travel. */
internal fun playerDismissCloses(drag: Float, threshold: Float): Boolean = drag >= threshold

/**
 * How far the drag has come. Held outside the player's own composition so that closing and
 * reopening quickly cannot leave the surface pushed down, and reset whenever the player appears
 * or goes away.
 */
internal class PlayerDismiss {
    val shown = Animatable(0f)
    /** The animation settling the surface, while one is still running. */
    var settle: Job? = null
}

@Composable
internal fun rememberPlayerDismiss(): PlayerDismiss = remember { PlayerDismiss() }

/**
 * Moves the surface with the drag, in its own layer: the predictive Back preview owns scale,
 * sideways drift and corners in a layer of its own, and neither writes what the other reads.
 * Drawn from the animation, so following a finger never recomposes the player.
 */
internal fun Modifier.playerDismiss(state: PlayerDismiss?): Modifier =
    if (state == null) this else graphicsLayer {
        val dropped = state.shown.value
        if (dropped > 0f) translationY = dropped
    }

/**
 * The drag itself, on the bar alone. The travel is kept inside the pointer handler rather than in
 * state, so following a finger recomposes nothing.
 *
 * [collapse] must read the app's current state when it is called rather than close over it: a drag
 * can end long after it began, and by then Lyrics may have opened over the player, or the player
 * may have gone.
 */
internal fun Modifier.dismissDrag(state: PlayerDismiss?, height: Float, collapse: () -> Unit): Modifier =
    if (state == null) this else pointerInput(height) {
        coroutineScope {
            var drag = 0f
            fun settleBack() { state.settle = launch { state.shown.animateTo(0f, motionShort()) } }
            detectVerticalDragGestures(
                // A new drag takes the surface over from one that is still settling.
                onDragStart = { drag = 0f; state.settle?.cancel() },
                onVerticalDrag = { _, amount ->
                    drag += amount
                    state.settle = launch { state.shown.snapTo(playerDismissOffset(drag, height)) }
                },
                onDragCancel = { settleBack() },
                onDragEnd = {
                    val closes = playerDismissCloses(drag, PLAYER_DISMISS_DROP.toPx())
                    // Always settles back, so the surface is never left pushed down behind the
                    // screen that replaced it, nor when the player is opened again.
                    settleBack()
                    if (closes) collapse()
                })
        }
    }
