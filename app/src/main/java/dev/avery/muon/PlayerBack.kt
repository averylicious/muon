package dev.avery.muon

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The open player shrinks and drifts while the system Back gesture previews closing it, by
 * [PLAYER_BACK_SHRINK] and [PLAYER_BACK_SLIDE] (Motion.kt). Material shrinks a surface by a fixed
 * distance rather than a fixed fraction, so the preview reads the same on a small phone as on a
 * tablet.
 *
 * One scale for both axes, measured against the width, because the artwork is square: shrinking the
 * two axes by different distances would stretch it. A surface narrower than the shrink distance
 * stops at nothing rather than turning inside out, and an unmeasured surface is left alone.
 */
internal fun playerBackScale(progress: Float, width: Float, shrink: Float): Float {
    if (width.isNaN() || width <= 0f) return 1f
    return 1f - minOf(shrink, width) * progress.coerceIn(0f, 1f) / width
}

/**
 * Follows the finger, as the platform does: a swipe from the left edge pushes the player right. A
 * button or hardware Back has no edge, and so nothing to follow.
 */
internal fun playerBackSlide(progress: Float, edge: Int, slide: Float): Float {
    val direction = when (edge) {
        BackEventCompat.EDGE_LEFT -> 1f
        BackEventCompat.EDGE_RIGHT -> -1f
        else -> 0f
    }
    return direction * slide * progress.coerceIn(0f, 1f)
}

/**
 * How much of the preview the player still holds when its sheet is [position] of the way closed.
 *
 * Nothing puts the preview back after a gesture is let go, so that the player leaves at the size
 * the gesture left it. Where the player then shrinks into the mini player ([morphing], PlayerMorph)
 * that would end with the panel scaled and shifted off the real mini player, so there it lets go in
 * step with the sheet and a closed sheet is exactly the mini player. A player that slides away below
 * the screen keeps all of it, as before.
 */
internal fun playerBackHeld(position: Float, morphing: Boolean): Float =
    if (!morphing) 1f else 1f - position.coerceIn(0f, 1f)

/**
 * Where [rect], given in the previewing surface's own space, is drawn: scaled about the middle of
 * the [surface] and then moved, exactly as the surface's layer is. The cover that flies between the
 * mini player and Now Playing is drawn outside that layer, and is placed with this so it stays on
 * the shrunken player instead of jumping to where an unshrunken one would have it.
 */
internal fun playerBackPlaced(rect: Rect, surface: Size, scale: Float, slideX: Float, slideY: Float): Rect {
    val x = surface.width / 2f
    val y = surface.height / 2f
    return Rect(x + (rect.left - x) * scale + slideX, y + (rect.top - y) * scale + slideY,
        x + (rect.right - x) * scale + slideX, y + (rect.bottom - y) * scale + slideY)
}

/**
 * How far a Back gesture over the player has come, which edge it started from and how far the
 * finger has since moved up or down. [shrink] and [slide] are its distances in pixels.
 */
internal class PlayerBackPreview(private val shrink: Float, private val slide: Float) {
    val shown = Animatable(0f)
    var edge by mutableIntStateOf(BackEventCompat.EDGE_NONE)
    private var drag by mutableFloatStateOf(0f)
    private var startY = Float.NaN
    /** The animation putting the player back after a cancelled gesture, while it is still running. */
    var restore: Job? = null

    /** A new gesture measures the finger's vertical travel from its own first event. */
    fun begin() { startY = Float.NaN }

    fun follow(event: BackEventCompat) {
        edge = event.swipeEdge
        if (startY.isNaN()) startY = event.touchY
        drag = event.touchY - startY
    }

    /** How far along the preview is drawn when [held] of it remains ([playerBackHeld]). */
    fun amount(held: Float): Float = (shown.value * held).coerceIn(0f, 1f)

    fun scale(width: Float, held: Float): Float = playerBackScale(amount(held), width, shrink)

    fun slideX(held: Float): Float = playerBackSlide(amount(held), edge, slide)

    /** Up or down with the finger, by no more than the sideways drift ([backFollow]). */
    fun slideY(height: Float, held: Float): Float = backFollow(drag, height) * slide * amount(held)

    /** [playerBackPlaced] for the preview as it stands. */
    fun placed(rect: Rect, surface: Size, held: Float): Rect =
        if (amount(held) <= 0f) rect
        else playerBackPlaced(rect, surface, scale(surface.width, held), slideX(held), slideY(surface.height, held))
}

/**
 * Registers the system Back gesture for the open player and reports its progress for drawing.
 *
 * Remember this inside the overlay's own transition, so the gesture state is created when the
 * player appears and discarded once it has gone rather than outliving it.
 *
 * [back] is the app's whole Back action rather than "close the player", and is read when the
 * gesture is let go rather than when it began: the handler passes the lambda it holds at the start
 * of a gesture to a coroutine that keeps it, so capturing it would commit a decision the app has
 * since moved on from. The caller decides whether a gesture whose target has gone acts at all.
 */
@Composable
internal fun rememberPlayerBackPreview(enabled: Boolean, back: () -> Unit): PlayerBackPreview {
    // Cancelling a gesture cancels the handler's own coroutine, so the animation that puts the
    // player back cannot run inside it.
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val preview = remember(density) {
        with(density) { PlayerBackPreview(PLAYER_BACK_SHRINK.toPx(), PLAYER_BACK_SLIDE.toPx()) }
    }
    val current by rememberUpdatedState(back)
    // Reopening the player before its closing animation ends reuses this composition, which would
    // otherwise still be holding the finished gesture's shrink. Nothing resets on the way out,
    // because the player should leave at the size the gesture left it.
    LaunchedEffect(enabled) { if (enabled) preview.shown.snapTo(0f) }
    PredictiveBackHandler(enabled) { gesture ->
        // A restore launched by the previous gesture can still be queued when this one starts, and
        // would then pull the preview back to rest under the finger. This gesture owns it now.
        preview.restore?.cancel()
        preview.begin()
        try {
            // Collected even when there is nothing to preview: a Back button completes the flow
            // immediately, with no events, and the handler requires it to be collected either way.
            gesture.collect {
                preview.follow(it)
                preview.shown.snapTo(PredictiveBackEasing.transform(it.progress.coerceIn(0f, 1f)))
            }
            current()
        } catch (cancelled: CancellationException) {
            preview.restore = scope.launch { preview.shown.animateTo(0f, motionSpatialFull()) }
        }
    }
    return preview
}

/**
 * Shrinks and drifts the whole surface, the way the platform treats a window it is about to
 * dismiss, so the tab underneath shows around it. Read in the draw phase, so following a finger
 * redraws the player without recomposing it. [held] is how much of the preview remains as the
 * sheet closes ([playerBackHeld]).
 */
internal fun Modifier.playerBackPreview(preview: PlayerBackPreview?, held: () -> Float = { 1f }): Modifier =
    if (preview == null) this else graphicsLayer {
        val remaining = held()
        val amount = preview.amount(remaining)
        if (amount <= 0f) return@graphicsLayer
        val scale = preview.scale(size.width, remaining)
        scaleX = scale
        scaleY = scale
        translationX = preview.slideX(remaining)
        translationY = preview.slideY(size.height, remaining)
        shape = RoundedCornerShape(PLAYER_BACK_CORNER * amount)
        clip = true
    }
