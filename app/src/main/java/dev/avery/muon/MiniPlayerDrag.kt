package dev.avery.muon

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dragging the mini player up opens Now Playing. Pulling it down is not this slice's business, so
 * it does not move at all.
 *
 * The finger is followed, but not step for step: the mini player is attached to the navigation bar
 * and lifting it far would just open a hole in the chrome. It gives a little and stops, the way a
 * sheet does when it is held back, and the decision to open is taken from how far the finger
 * actually travelled rather than from how far the surface was allowed to move.
 */
internal val MINI_DRAG_LIFT: Dp = 24.dp
internal val MINI_DRAG_OPEN: Dp = 48.dp
internal const val MINI_DRAG_RESISTANCE = 3f

/**
 * How far the surface is drawn from its resting place, in pixels and negative upwards, for a
 * cumulative [drag] that is itself negative upwards.
 */
internal fun miniDragOffset(drag: Float, maximum: Float): Float =
    if (drag >= 0f) 0f else -minOf(-drag / MINI_DRAG_RESISTANCE, maximum)

/** Whether letting go here opens the player, judged on the finger's own travel. */
internal fun miniDragOpens(drag: Float, threshold: Float, eligible: Boolean = true): Boolean =
    eligible && -drag >= threshold
