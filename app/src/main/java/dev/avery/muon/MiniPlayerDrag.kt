package dev.avery.muon

import androidx.compose.ui.input.pointer.util.VelocityTracker1D
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
 * One finger's gesture on the mini player, from touch-down to lift, for judging its release.
 *
 * A brief flick produces very few pointer events. Android batches movement per frame, and the slop
 * helper reports only the event that crossed the touch slop — intermediate events before it are not
 * visible — so a flick of a few frames may reach this as the touch-down, one slop-crossing event
 * (with whatever historical points it batched) and the lift. What is recorded is exactly that:
 * the touch-down, the slop-crossing event's historical points and position, each later move with its
 * historical points, and the lift if it moved.
 *
 * Velocity comes from the tracker's `Impulse` strategy (`VelocityTracker1D(isDataDifferential =
 * false)`, ui 1.9.3), which estimates from two samples. The 2D tracker's default `Lsq2` needs three and
 * returns nothing with fewer; recording only post-slop moves under it is what made short flicks read as
 * motionless. A sample is added only where the finger actually moved: a lift reported at the last
 * move's position would otherwise tell the tracker the finger had stopped, when all it did was lift.
 *
 * The trace belongs to the finger that touched down. The drag helpers hand a gesture over to another
 * pressed finger when the first one lifts; a sample from any other finger abandons the trace, so another
 * finger's absolute position can never be read as this one's travel or speed. An abandoned trace never
 * commits anything.
 *
 * Positions are the mini player's own, which do not move while it carries a preview. Times are pointer
 * event times throughout, including the lift, so a finger that stopped before lifting is judged by when
 * it stopped and when it lifted, not by when the app processed the release.
 */
internal class FlickTrace {
    private val tracker = VelocityTracker1D(isDataDifferential = false)
    private var finger = 0L
    private var downY = 0f
    private var lastY = 0f
    private var lastMoveMillis = 0L

    /** Set once a sample arrives from another finger; the trace then counts for nothing. */
    var abandoned = false
        private set

    fun down(finger: Long, timeMillis: Long, y: Float) {
        tracker.resetTracking()
        this.finger = finger
        abandoned = false
        downY = y
        lastY = y
        lastMoveMillis = timeMillis
        tracker.addDataPoint(timeMillis, y)
    }

    /** Adds a sample from [finger]; returns false, abandoning the trace, if it is another finger. */
    fun sample(finger: Long, timeMillis: Long, y: Float): Boolean {
        if (abandoned || finger != this.finger) {
            abandoned = true
            return false
        }
        if (y != lastY) {
            lastY = y
            lastMoveMillis = timeMillis
            tracker.addDataPoint(timeMillis, y)
        }
        return true
    }

    /** The finger's travel since touch-down, negative upwards, including the part spent in the slop. */
    val travel: Float get() = if (abandoned) 0f else lastY - downY

    /** Velocity at a lift at [upMillis], in pixels per second, negative upwards; none if it had stopped. */
    fun releaseVelocity(upMillis: Long): Float =
        if (abandoned) 0f else sheetReleaseVelocity(tracker.calculateVelocity(), lastMoveMillis, upMillis)
}
