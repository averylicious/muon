package dev.avery.muon

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.util.VelocityTracker
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

/**
 * One gesture on the mini player, from touch-down to lift, for judging its release.
 *
 * A brief flick produces very few pointer events: Android batches movement per frame, so a flick of a
 * few frames may reach the app as the touch-down, one event crossing the touch slop and the lift. The
 * velocity tracker fits a curve and needs at least three samples inside its window, returning no
 * velocity at all otherwise (ui 1.9.3, `VelocityTracker1D.calculateVelocity`). Recording only moves
 * after the slop left a short flick with too few, so it read as motionless and fell back. This keeps
 * every sample the platform provides: the touch-down, each event's batched historical points, the
 * moves, and the lift itself.
 *
 * Positions are the mini player's own, which do not move while it carries a preview, so they are
 * the finger's movement and nothing else. Times are pointer event times throughout, including the
 * lift, so a finger that stopped before lifting is judged by when it stopped and when it lifted, not
 * by when the app got round to processing the release.
 */
internal class FlickTrace {
    private val tracker = VelocityTracker()
    private var downY = 0f
    private var lastY = 0f
    private var lastMoveMillis = 0L

    fun down(timeMillis: Long, y: Float) {
        tracker.resetTracking()
        downY = y
        lastY = y
        lastMoveMillis = timeMillis
        tracker.addPosition(timeMillis, Offset(0f, y))
    }

    fun sample(timeMillis: Long, y: Float) {
        if (y != lastY) {
            lastY = y
            lastMoveMillis = timeMillis
        }
        tracker.addPosition(timeMillis, Offset(0f, y))
    }

    /** The finger's travel since touch-down, negative upwards, including the part spent in the slop. */
    val travel: Float get() = lastY - downY

    /** Velocity at a lift at [upMillis], in pixels per second, negative upwards; none if it had stopped. */
    fun releaseVelocity(upMillis: Long): Float =
        sheetReleaseVelocity(tracker.calculateVelocity().y, lastMoveMillis, upMillis)
}
