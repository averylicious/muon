package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 * Whether the drag that is ending may still put the player away. [startedAt] is the presentation
 * the finger went down on and [now] the one it came up on: a drag is only ever good for the player
 * it began on, never for the one that replaced it.
 */
internal fun playerDismissCommits(startedAt: Int, now: Int, drag: Float, threshold: Float): Boolean =
    startedAt == now && playerDismissCloses(drag, threshold)

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
    /** Set by a drag that put the player away, so its parting offset is not taken back off it. */
    var committed = false

    /**
     * Gives up whatever was moving the surface. Worth doing even when the surface is already at
     * rest, because a move can be waiting to run and would otherwise land on the next player.
     */
    fun stop() {
        settle?.cancel()
        settle = null
    }

    /** Follows the finger. Runs on the app's scope for the same reason the settle does. */
    fun moveTo(offset: Float) {
        stop()
        settle = scope.launch { shown.snapTo(offset) }
    }

    /** Puts the surface back where it rests. */
    fun settleBack() {
        stop()
        settle = scope.launch { shown.animateTo(0f, motionShort()) }
    }

    /** The player is on screen again: forget any drag aimed at the one before it. */
    suspend fun present() {
        stop()
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
 * The dragged player's top edge: rounded like the sheet in the opening mockup, lifted by Material's
 * own modal-sheet elevation (Level1 in material3 1.4.0). Both grow over the first pixels of a drag
 * rather than appearing at once, and are nothing at rest, so the open player shows no corners or
 * shadow tucked under the status bar.
 */
internal val PLAYER_SHEET_CORNER: Dp = 28.dp
internal val PLAYER_SHEET_ELEVATION: Dp = 1.dp
internal val PLAYER_SHEET_EDGE: Dp = 1.dp

/**
 * Whether the dragged player draws an outline along its top edge. Only over pure black: there the
 * player and library are both black, so neither the scrim nor a shadow can tell them apart, and
 * only while the player is displaced, so the open player stays truly black edge to edge.
 */
internal fun playerSheetEdgeShown(dropped: Float, pureBlack: Boolean): Boolean = pureBlack && dropped > 0f

/**
 * How far the player's content may rise inside a dragged sheet. The top system inset keeps content
 * clear of the status bar and any cutout, but once the sheet's top edge has dropped below the
 * screen's physical top, that much of the inset no longer covers anything and only reads as blank
 * space above the handle. Never more than the inset, so content cannot rise into what the inset
 * still protects against, and nothing at rest.
 */
internal fun playerInsetReclaimed(dropped: Float, topInset: Float): Float =
    dropped.coerceIn(0f, maxOf(topInset, 0f))

/**
 * Lets the player's content rise by [playerInsetReclaimed] as the sheet is dragged down, reading the
 * drag in the placement phase only. It moves placement rather than padding on purpose: the content
 * keeps the size it was measured at, because changing its constraints mid-drag would restart the
 * drag's own detector, which is keyed on the player's height, and could flip the player between its
 * fixed and scrolling layouts, jumping the artwork. Only the top inset is reclaimed; the sides and
 * bottom keep their full protection.
 */
internal fun Modifier.reclaimTopInset(state: PlayerDismiss?, insets: WindowInsets): Modifier =
    if (state == null) this else offset {
        IntOffset(0, -playerInsetReclaimed(state.shown.value, insets.getTop(this).toFloat()).roundToInt())
    }

/**
 * Moves the surface with the drag, in its own layer: the predictive Back preview owns scale,
 * sideways drift and corners in a layer of its own, and neither writes what the other reads.
 * Drawn from the animation, so following a finger never recomposes the player.
 *
 * [edge] is the outline colour for a pure black theme, or null where the scrim and shadow already
 * separate the player from the library.
 */
internal fun Modifier.playerDismiss(state: PlayerDismiss?, edge: Color? = null): Modifier =
    if (state == null) this else {
        val outline = Path()
        graphicsLayer {
            val dropped = state.shown.value
            if (dropped <= 0f) return@graphicsLayer
            translationY = dropped
            val corner = minOf(dropped, PLAYER_SHEET_CORNER.toPx())
            shape = RoundedCornerShape(topStart = corner, topEnd = corner)
            clip = true
            // A layer draws its own shadow outside its clip, so the edge stays visible.
            shadowElevation = minOf(dropped, PLAYER_SHEET_ELEVATION.toPx())
        }.drawWithContent {
            drawContent()
            val dropped = state.shown.value
            if (!playerSheetEdgeShown(dropped, pureBlack = edge != null) || edge == null) return@drawWithContent
            drawTopEdge(outline, edge, minOf(dropped, PLAYER_SHEET_CORNER.toPx()), PLAYER_SHEET_EDGE.toPx())
        }
    }

/**
 * Traces the top of the sheet along the same corners the layer clips to. Drawn half a stroke inside
 * the edge, on a concentric radius, so the clip cannot shave the line thin; the sides and bottom are
 * left alone, because they sit at the screen's own edges.
 */
private fun DrawScope.drawTopEdge(path: Path, color: Color, corner: Float, width: Float) {
    val inset = width / 2f
    val radius = (corner - inset).coerceAtLeast(0f)
    val right = size.width - inset
    path.reset()
    path.moveTo(inset, inset + radius)
    if (radius > 0f) path.arcTo(Rect(inset, inset, inset + 2 * radius, inset + 2 * radius), 180f, 90f, false)
    path.lineTo(right - radius, inset)
    if (radius > 0f) path.arcTo(Rect(right - 2 * radius, inset, right, inset + 2 * radius), 270f, 90f, false)
    drawPath(path, color, style = Stroke(width))
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
    // No state means the player is not on screen to be dragged. The detector goes with it, so a
    // drag cannot outlive the player it began on and carry on moving the next one: it is torn
    // down when the player leaves and built again when the next one arrives.
    if (state == null) this else pointerInput(state, height) {
        var drag = 0f
        var startedAt = state.generation
        try {
            detectVerticalDragGestures(
                onDragStart = {
                    // A new drag takes the surface over from whatever was still moving it,
                    // including a settle left behind by the detector before this one.
                    state.stop()
                    drag = 0f
                    startedAt = state.generation
                    state.committed = false
                },
                onVerticalDrag = { _, amount ->
                    drag += amount
                    state.moveTo(playerDismissOffset(drag, height))
                },
                onDragCancel = { state.settleBack() },
                onDragEnd = {
                    // A pull that puts the player away leaves the surface where the finger left
                    // it, so the closing animation carries on down from there rather than
                    // snatching it back first. Opening the player again winds it back.
                    if (playerDismissCommits(startedAt, state.generation, drag, PLAYER_DISMISS_DROP.toPx())) {
                        state.committed = true
                        collapse()
                    } else state.settleBack()
                })
        } finally {
            // Resizing the window restarts this detector, and the player leaving tears it down,
            // but the offset it was moving lives on outside it. Give up this detector's work
            // whatever the surface is showing, because a move can still be waiting to run, and
            // put the surface back unless a drag has just sent the player away with it.
            state.stop()
            if (!state.committed) state.settleBack()
        }
    }
