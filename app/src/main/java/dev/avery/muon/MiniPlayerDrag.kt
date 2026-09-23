package dev.avery.muon

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dragging the mini player up opens Now Playing, with the whole player rising under the finger
 * through the player's sheet. Pulling it down is not this gesture's business, so it does not move.
 *
 * The decision to open is taken from how far and how fast the finger actually travelled, not from
 * where the sheet happens to be. This is the least a slow release must travel; on a phone the share
 * of the sheet's height in [sheetReleaseDistance] asks for more.
 */
internal val MINI_DRAG_OPEN: Dp = 48.dp

/** Whether letting go here opens the player, judged on the finger's own upward travel and speed. */
internal fun miniDragOpens(drag: Float, threshold: Float, eligible: Boolean = true, velocity: Float = 0f,
    flick: SheetFlick = SheetFlick.None): Boolean =
    eligible && sheetReleaseCommits(-drag, -velocity, threshold, flick)

/**
 * Whether a drag from the mini player may begin carrying the player up. Only once the finger's own
 * travel is upwards: a drag that crosses the touch slop downwards is not an opening, so it begins no
 * preview, shows no scrim and leaves the library in the accessibility tree.
 */
internal fun playerPreviewMayBegin(travel: Float): Boolean = travel < 0f
