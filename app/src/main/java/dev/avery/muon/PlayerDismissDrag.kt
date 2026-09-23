package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
 * Where a dragged sheet sits, as a fraction of its height. [baseline] is where the sheet already was
 * when the finger took it over — part-way through opening, or through settling back — and [travel]
 * is the finger's own movement since, positive downwards. It follows the finger exactly from that
 * baseline, so grabbing a moving sheet never snaps it, and it never rises above open or falls past
 * closed. A sheet not yet measured stays where it was rather than dividing by nothing.
 *
 * Only [travel] decides whether the drag closes the player (see [playerDismissCommits]); the baseline
 * is where the sheet happened to be, not something the finger did.
 */
internal fun playerSheetDragged(baseline: Float, travel: Float, height: Float): Float =
    if (height > 0f) (baseline + travel / height).coerceIn(0f, 1f) else baseline.coerceIn(0f, 1f)

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
 * Whether the player's host is composed. The logical open state mounts it at once, even at zero
 * progress, so an opening sheet can be measured and animated in; otherwise it would wait for
 * progress that only a mounted sheet can make. A sheet still on screen stays mounted while it leaves.
 */
internal fun playerSheetPresent(open: Boolean, onScreen: Boolean): Boolean = open || onScreen

/**
 * Whether a preview still belongs to the gesture that began it. Back ends a preview at once, and a
 * new presentation bumps the generation, so a finger still down afterwards can neither move nor open
 * the player.
 */
internal fun playerPreviewOwned(previewing: Boolean, startedAt: Int, now: Int): Boolean =
    previewing && startedAt == now

/**
 * Whether letting go of a preview opens the player: only a preview still owned by this gesture, only
 * while the player may be opened at all, and only for 48 dp of the finger's own travel upwards —
 * where the sheet already was when the finger took it does not count.
 */
internal fun playerPreviewOpens(previewing: Boolean, startedAt: Int, now: Int, travel: Float,
    threshold: Float, eligible: Boolean): Boolean =
    playerPreviewOwned(previewing, startedAt, now) && miniDragOpens(travel, threshold, eligible)

/**
 * How previews and presentations are ordered, kept pure so the sequencing itself can be tested.
 *
 * Each preview gets a [generation] of its own when it begins, as does each presentation, so a gesture
 * cancelled and restarted before anything is presented cannot share a token with the one before it.
 * Each preview that ends adds to [completions], so its ending is a new value even if the preview began
 * and ended before composition ever saw it running; whatever settles the sheet keys on that, not on a
 * flag that may have flipped and flipped back unseen.
 */
internal data class SheetTurn(val generation: Int = 0, val previewing: Boolean = false,
    val completions: Int = 0) {
    fun begin() = copy(generation = generation + 1, previewing = true)
    fun end() = if (previewing) copy(previewing = false, completions = completions + 1) else this
    fun present() = copy(generation = generation + 1)
}

/**
 * Where the player sits: the single owner of its vertical position, for opening and closing as much
 * as for a drag, so no two translations ever add up.
 *
 * [position] is a fraction of the sheet's own height, `0` open and `1` closed. A fraction because an
 * opening sheet is composed before it is measured, and because it stays right if the window changes
 * size mid-animation. Its [scope] belongs to the app rather than to a gesture detector, so motion
 * outlives a detector that is torn down or restarted.
 */
internal class PlayerSheet(private val scope: CoroutineScope, openAtStart: Boolean) {
    val position = Animatable(if (openAtStart) 0f else 1f)
    /** Whether any of the sheet is on screen. Composition sees this flip, never the float itself. */
    val onScreen by derivedStateOf { position.value < 1f }
    /** The sheet's measured height, for turning finger travel into [position]. */
    var height = 0f
    /** The one animation or drag moving the sheet, while it still runs. */
    var settle: Job? = null
        private set
    /**
     * The ordering of previews and presentations, as one immutable value in snapshot state: every
     * change replaces it, so composition sees each preview's completion even if it began and ended
     * between two frames, and every owner holds a token no other gesture can share.
     */
    var turn by mutableStateOf(SheetTurn())
        private set
    /** Which presentation or preview is current. A drag is only good for the one it began in. */
    val generation get() = turn.generation
    /** Whether the player is logically open, as opposed to leaving or gone. */
    var open = openAtStart
        private set
    /**
     * Whether the finger is carrying a closed player up from the mini player. Snapshot state that
     * flips twice per gesture, so composition can react to it without ever reading the position.
     */
    val previewing get() = turn.previewing

    /**
     * The finger takes a closed player up from the mini player. Holds the sheet where it is, which is
     * itself motion the preview owns, and returns the presentation it began on, so callbacks arriving
     * after Back or a change of presentation can be recognised and refused.
     */
    fun beginPreview(): Int {
        moveTo(position.value)
        turn = turn.begin()
        return turn.generation
    }

    /**
     * The preview is over, however it ended. The position is left where it is: the presentation
     * decides what happens next, opening it if the player was opened and putting it away otherwise.
     */
    fun endPreview() {
        turn = turn.end()
    }

    /**
     * Gives up whatever was moving the sheet. Worth doing even when it is already at rest, because a
     * move can be waiting to run and would otherwise land on the next presentation.
     */
    fun stop() {
        settle?.cancel()
        settle = null
    }

    /** Follows the finger, returning the job so the detector can tell later whether it still owns it. */
    fun moveTo(fraction: Float): Job {
        stop()
        return scope.launch { position.snapTo(fraction) }.also { settle = it }
    }

    /** Puts the sheet back where an open player rests. */
    fun settleBack(): Job {
        stop()
        return scope.launch { position.animateTo(0f, motionShort()) }.also { settle = it }
    }

    /**
     * The player has been opened or closed. Every change is a new presentation, so a drag begun on the
     * last one cannot act on this one, and the sheet animates from wherever it is — so a drag that put
     * the player away carries on down from where the finger left it, and a quick reopen turns back.
     */
    fun present(open: Boolean) {
        turn = turn.present()
        this.open = open
        stop()
        settle = scope.launch { position.animateTo(if (open) 0f else 1f, motionMedium()) }
    }
}

@Composable
internal fun rememberPlayerSheet(openAtStart: Boolean): PlayerSheet {
    val scope = rememberCoroutineScope()
    // Opened at start when restored open, so a rotation does not replay the opening.
    return remember(scope) { PlayerSheet(scope, openAtStart) }
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
internal fun Modifier.reclaimTopInset(state: PlayerSheet?, insets: WindowInsets): Modifier =
    if (state == null) this else offset {
        val dropped = state.position.value * state.height
        IntOffset(0, -playerInsetReclaimed(dropped, insets.getTop(this).toFloat()).roundToInt())
    }

/**
 * Moves the surface with the drag, in its own layer: the predictive Back preview owns scale,
 * sideways drift and corners in a layer of its own, and neither writes what the other reads.
 * Drawn from the animation, so following a finger never recomposes the player.
 *
 * [edge] is the outline colour for a pure black theme, or null where the scrim and shadow already
 * separate the player from the library.
 */
internal fun Modifier.playerSheet(state: PlayerSheet, edge: Color? = null): Modifier =
    run {
        val outline = Path()
        graphicsLayer {
            // The only vertical translation the player has: opening, closing and dragging alike.
            val dropped = state.position.value * size.height
            if (dropped <= 0f) return@graphicsLayer
            translationY = dropped
            val corner = minOf(dropped, PLAYER_SHEET_CORNER.toPx())
            shape = RoundedCornerShape(topStart = corner, topEnd = corner)
            clip = true
            // A layer draws its own shadow outside its clip, so the edge stays visible.
            shadowElevation = minOf(dropped, PLAYER_SHEET_ELEVATION.toPx())
        }.drawWithContent {
            drawContent()
            val dropped = state.position.value * size.height
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
internal fun Modifier.dismissDrag(state: PlayerSheet?, height: Float, collapse: () -> Unit): Modifier =
    // No state means the player is not on screen to be dragged. The detector goes with it, so a
    // drag cannot outlive the player it began on and carry on moving the next one: it is torn
    // down when the player leaves and built again when the next one arrives.
    if (state == null) this else pointerInput(state, height) {
        // The finger's own travel since it took the sheet over, kept apart from where the sheet
        // already was, because only the finger's travel counts towards closing.
        var drag = 0f
        var baseline = 0f
        var startedAt = state.generation
        // The last motion this detector owns, so it only ever cleans up after itself.
        var mine: Job? = null
        // Only an open player rests at the top; a closing one belongs to the presentation.
        fun restore() { if (state.open) mine = state.settleBack() }
        try {
            detectVerticalDragGestures(
                onDragStart = {
                    // A new drag takes the sheet over from whatever was still moving it, including the
                    // opening animation or a settle left by the last detector, and holds it exactly
                    // where it was. Holding it is itself motion this detector owns, so a detector torn
                    // down before the finger has moved still puts back the sheet it stopped.
                    baseline = state.position.value
                    drag = 0f
                    startedAt = state.generation
                    mine = state.moveTo(baseline)
                },
                onVerticalDrag = { _, amount ->
                    drag += amount
                    mine = state.moveTo(playerSheetDragged(baseline, drag, state.height))
                },
                onDragCancel = { restore() },
                onDragEnd = {
                    // A pull that puts the player away leaves the sheet where the finger left it and
                    // gives up ownership: closing hands the position to the presentation, which
                    // carries on down from there, and this detector must not tidy it back up.
                    if (playerDismissCommits(startedAt, state.generation, drag, PLAYER_DISMISS_DROP.toPx())) {
                        mine = null
                        collapse()
                    } else restore()
                })
        } finally {
            // Resizing the window restarts this detector, and the player leaving tears it down.
            // Clean up only if this detector's motion is still the one running: if the player has
            // been closed or reopened since, that animation belongs to the presentation and must
            // not be cancelled, or the sheet would stop half-way and stay mounted. Put the sheet
            // back only while the player is still open.
            if (mine != null && state.settle === mine) {
                state.stop()
                restore()
            }
        }
    }
