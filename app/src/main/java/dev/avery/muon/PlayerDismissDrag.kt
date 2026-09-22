package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Whether the drag that is ending may still put the player away.
 *
 * The gesture outlives the presentation it began in: the detector survives the player's closing
 * animation, so a player that closed and opened again would otherwise be dismissed by a drag aimed
 * at the one before it. [startedAt] is the presentation the finger went down on and [now] the one
 * it came up on, and a drag that did not [engage] on a player that was actually on screen never
 * counted in the first place.
 */
internal fun playerDismissCommits(engaged: Boolean, startedAt: Int, now: Int, drag: Float,
    threshold: Float): Boolean = engaged && startedAt == now && playerDismissCloses(drag, threshold)

/**
 * How far the drag has come, held outside the player's own composition so that it survives the
 * closing animation.
 *
 * Its [scope] belongs to the app rather than to the gesture detector, because the detector is
 * cancelled when the window resizes or the player goes away, and the settle that puts the surface
 * back must outlive that: the offset lives on here, so an abandoned drag would otherwise be left
 * stranded on screen.
 */
internal class PlayerDismiss(private val scope: CoroutineScope) {
    val shown = Animatable(0f)
    /** The animation settling the surface, or the drag following the finger, while it still runs. */
    var settle: Job? = null
    /** Which presentation of the player is on screen. A drag is only good for the one it began in. */
    var generation = 0
    /** Whether the player is on screen to be dragged at all, rather than on its way off. */
    var presented = false
    /** Set by a drag that put the player away, so its parting offset is not taken back off it. */
    var committed = false

    /** Follows the finger. Runs on the app's scope for the same reason the settle does. */
    fun moveTo(offset: Float) {
        settle = scope.launch { shown.snapTo(offset) }
    }

    /** Cancels whatever was moving the surface and puts it back where it rests. */
    fun settleBack() {
        settle?.cancel()
        settle = scope.launch { shown.animateTo(0f, motionShort()) }
    }

    /** The player is on screen again: forget any drag aimed at the one before it. */
    suspend fun present() {
        settle?.cancel()
        committed = false
        shown.snapTo(0f)
    }
}

@Composable
internal fun rememberPlayerDismiss(): PlayerDismiss {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerDismiss(scope) }
}

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
        var drag = 0f
        var startedAt = state.generation
        var engaged = false
        try {
            detectVerticalDragGestures(
                onDragStart = {
                    // A new drag takes the surface over from one that is still settling, and a
                    // player already on its way off the screen accepts none at all.
                    state.settle?.cancel()
                    drag = 0f
                    startedAt = state.generation
                    engaged = state.presented
                    // Only a drag that takes hold clears the last one's parting offset; a touch
                    // on a player already leaving must not claw it back.
                    if (engaged) state.committed = false
                },
                onVerticalDrag = { _, amount ->
                    if (engaged) {
                        drag += amount
                        state.moveTo(playerDismissOffset(drag, height))
                    }
                },
                onDragCancel = { if (engaged) state.settleBack() },
                onDragEnd = {
                    // A pull that puts the player away leaves the surface where the finger left
                    // it, so the closing animation carries on down from there rather than
                    // snatching it back first. Opening the player again winds it back.
                    if (playerDismissCommits(engaged, startedAt, state.generation, drag,
                            PLAYER_DISMISS_DROP.toPx())) {
                        state.committed = true
                        collapse()
                    } else if (engaged) state.settleBack()
                })
        } finally {
            // Resizing the window restarts this detector and cancels whatever it was running, but
            // the offset it was moving lives on outside it. Put the surface back through the app's
            // own scope, unless a drag has just sent the player away with it.
            if (!state.committed && state.shown.value != 0f) state.settleBack()
        }
    }
