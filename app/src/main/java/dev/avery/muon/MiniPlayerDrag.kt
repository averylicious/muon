package dev.avery.muon

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dragging the mini player up opens Now Playing, with the whole player rising under the finger
 * through the player's sheet. Pulling it down is not this gesture's business, so it does not move.
 *
 * The decision to open is taken from how far the finger actually travelled, not from where the
 * sheet happens to be.
 */
internal val MINI_DRAG_OPEN: Dp = 48.dp

/** Whether letting go here opens the player, judged on the finger's own travel. */
internal fun miniDragOpens(drag: Float, threshold: Float, eligible: Boolean = true): Boolean =
    eligible && -drag >= threshold
