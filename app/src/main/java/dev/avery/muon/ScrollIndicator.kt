package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal val INDICATOR_WIDTH = 4.dp
internal val INDICATOR_MIN_LENGTH = 32.dp
internal val INDICATOR_MARGIN = 4.dp

/** How long the indicator stays after scrolling stops, so a glance after a fling still finds it. */
internal const val INDICATOR_LINGER_MS = 900L

/** The thumb's start and length along the list's height, in pixels. */
internal data class ScrollThumb(val start: Float, val length: Float)

/**
 * The thumb for a lazy list, estimated from the average size of the rows on screen, since a lazy list
 * never measures the rows it has not shown. The library's rows are nearly uniform, so the estimate is
 * close; at either end it is exact, because being at the top or bottom is known rather than estimated.
 * Nothing is drawn when the list fits on screen or has nothing measured yet.
 */
internal fun scrollThumb(firstIndex: Int, firstOffset: Int, averageItem: Float, total: Int, viewport: Float,
    minLength: Float, atStart: Boolean, atEnd: Boolean): ScrollThumb? {
    if (total <= 0 || viewport <= 0f || averageItem <= 0f || (atStart && atEnd)) return null
    val content = averageItem * total
    if (content <= viewport) return null
    val length = (viewport * viewport / content).coerceIn(minOf(minLength, viewport), viewport)
    val fraction = when {
        atStart -> 0f
        atEnd -> 1f
        else -> ((firstIndex * averageItem + firstOffset) / (content - viewport)).coerceIn(0f, 1f)
    }
    return ScrollThumb(fraction * (viewport - length), length)
}

/**
 * A thin, passive scroll indicator for long lists: where you are, and how close the end is. It is a
 * stopgap until the lists can be sorted, which is what would make a draggable letter scroller worth
 * having; it takes no touches and is invisible to TalkBack, which has its own scroll position.
 * [shown] fades it in while the list moves and out a moment after it stops.
 */
internal class ScrollIndicator(val state: LazyListState) {
    val shown = Animatable(0f)
    /** A finger is on the A–Z handle: stay shown, however still the list is. */
    var held by mutableStateOf(false)
    /** Where the finger has put the thumb, 0 at the top to 1 at the bottom, while it holds it. */
    var dragFraction by mutableStateOf<Float?>(null)

    /**
     * The thumb for a list [height] pixels tall, relative to a track inset by [margin] at each end.
     * While a finger holds it, it sits where the finger put it rather than where the estimate says.
     */
    fun thumb(height: Float, margin: Float, minLength: Float): ScrollThumb? {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return null
        val average = visible.sumOf { it.size }.toFloat() / visible.size + info.mainAxisItemSpacing
        val track = height - 2 * margin
        val thumb = scrollThumb(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, average,
            info.totalItemsCount, track, minLength,
            atStart = !state.canScrollBackward, atEnd = !state.canScrollForward) ?: return null
        val held = dragFraction ?: return thumb
        return ScrollThumb(held.coerceIn(0f, 1f) * (track - thumb.length), thumb.length)
    }
}

@Composable
internal fun rememberScrollIndicator(state: LazyListState): ScrollIndicator {
    val indicator = remember(state) { ScrollIndicator(state) }
    // Like every animation it follows the system animation scale, and snaps when animations are off.
    LaunchedEffect(indicator) {
        snapshotFlow { state.isScrollInProgress || indicator.held }.collectLatest { scrolling ->
            if (scrolling) indicator.shown.animateTo(1f, motionShort())
            else {
                delay(INDICATOR_LINGER_MS)
                indicator.shown.animateTo(0f, motionMedium())
            }
        }
    }
    return indicator
}

/**
 * Draws the indicator over the list, at its end edge (right, or left in a right-to-left layout). Read
 * in the draw phase, so scrolling redraws the thumb without recomposing the list.
 */
internal fun Modifier.scrollIndicator(indicator: ScrollIndicator, color: Color,
    width: Dp = INDICATOR_WIDTH, minLength: Dp = INDICATOR_MIN_LENGTH, bottomInset: Dp = 0.dp): Modifier = drawWithContent {
    drawContent()
    val alpha = indicator.shown.value
    if (alpha <= 0f) return@drawWithContent
    val margin = INDICATOR_MARGIN.toPx()
    // [bottomInset] is the part of the list behind glass bars (LocalUnderBars): the track stops above it.
    val track = (size.height - bottomInset.toPx()).coerceAtLeast(0f)
    val thumb = indicator.thumb(track, margin, minLength.toPx()) ?: return@drawWithContent
    val width = width.toPx()
    val x = if (layoutDirection == LayoutDirection.Rtl) margin else size.width - margin - width
    drawRoundRect(color, topLeft = Offset(x, margin + thumb.start), size = Size(width, thumb.length),
        cornerRadius = CornerRadius(width / 2), alpha = alpha)
}
