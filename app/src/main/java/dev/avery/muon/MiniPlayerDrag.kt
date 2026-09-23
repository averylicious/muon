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

/**
 * Whether a drag from the mini player may begin carrying the player up. Only once the finger's own
 * travel is upwards: a drag that crosses the touch slop downwards is not an opening, so it begins no
 * preview, shows no scrim and leaves the library in the accessibility tree.
 */
internal fun playerPreviewMayBegin(travel: Float): Boolean = travel < 0f
