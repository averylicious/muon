package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import kotlin.math.roundToInt

/** The A–Z thumb is wider and longer than the passive one, because it is meant to be grabbed. */
internal val SCROLLER_WIDTH = 6.dp
internal val SCROLLER_MIN_LENGTH = 48.dp
private val SCROLLER_TOUCH = 48.dp
private val BUBBLE = 56.dp

/**
 * The letter a title files under: its first letter, uppercased without accents ("élan" is E), or
 * `#` for a title that starts with a digit or a symbol. Scripts without case, such as CJK, give their
 * first character. A blank title files under `#`.
 */
internal fun sectionLetter(title: String): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "#"
    val first = String(Character.toChars(trimmed.codePointAt(0)))
    if (!Character.isLetter(first.codePointAt(0))) return "#"
    val base = Normalizer.normalize(first, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    return base.ifEmpty { first }.uppercase(java.util.Locale.ROOT)
}

/** The row a thumb [fraction] of the way down a list of [count] rows points at. */
internal fun scrollerIndex(fraction: Float, count: Int): Int =
    if (count <= 0) 0 else (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt()

/** A resized viewport can be shorter than the handle/bubble; pin it at zero in that case. */
internal fun scrollerOffset(center: Float, element: Float, viewport: Float): Float =
    (center - element / 2).coerceIn(0f, (viewport - element).coerceAtLeast(0f))

/**
 * A draggable handle over the list's thumb, for jumping through an alphabetical list, and a bubble
 * with the letter under the finger. It exists only while the thumb is showing, so the rest of the
 * time it takes no touches from the rows beneath it. While a finger holds it, the handle stays where
 * the drag began — a target that moved with the finger would shrink the finger's own movement — and
 * the drawn thumb and the bubble follow the finger instead. A touch affordance only: TalkBack already
 * scrolls the list, so the handle is left out of the accessibility tree.
 */
@Composable
internal fun BoxScope.AlphabetScroller(indicator: ScrollIndicator, count: Int, section: (Int) -> String) {
    val active by remember(indicator) { derivedStateOf { indicator.held || indicator.shown.value > 0.01f } }
    if (!active || count <= 0) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val height = constraints.maxHeight.toFloat()
        val margin = with(density) { INDICATOR_MARGIN.toPx() }
        val minLength = with(density) { SCROLLER_MIN_LENGTH.toPx() }
        val touch = with(density) { SCROLLER_TOUCH.toPx() }
        var frozenCenter by remember { mutableStateOf<Float?>(null) }
        var letter by remember { mutableStateOf("") }
        // Read through these by the gesture, which outlives the composition that started it: a
        // rotation changes the height, and a refresh the rows, while a finger may be down.
        val currentHeight by rememberUpdatedState(height)
        val currentCount by rememberUpdatedState(count)
        val currentSection by rememberUpdatedState(section)
        fun center(h: Float): Float? = indicator.thumb(h, margin, minLength)?.let { margin + it.start + it.length / 2 }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val end = if (rtl) Alignment.TopStart else Alignment.TopEnd
        Box(Modifier.align(end)
            .offset { IntOffset(0, scrollerOffset(frozenCenter ?: center(height) ?: 0f, touch, height).roundToInt()) }
            .size(SCROLLER_TOUCH)
            .clearAndSetSemantics {}
            .pointerInput(indicator) {
                var fraction = 0f
                fun release() { indicator.held = false; indicator.dragFraction = null; frozenCenter = null }
                detectVerticalDragGestures(
                    onDragStart = {
                        val h = currentHeight
                        val thumb = indicator.thumb(h, margin, minLength)
                        val range = (h - 2 * margin - (thumb?.length ?: 0f)).coerceAtLeast(1f)
                        fraction = ((thumb?.start ?: 0f) / range).coerceIn(0f, 1f)
                        frozenCenter = center(h)
                        indicator.held = true
                        indicator.dragFraction = fraction
                        letter = currentSection(scrollerIndex(fraction, currentCount))
                    },
                    onDragEnd = { release() },
                    onDragCancel = { release() },
                ) { change, dy ->
                    change.consume()
                    val h = currentHeight
                    val thumb = indicator.thumb(h, margin, minLength)
                    val range = (h - 2 * margin - (thumb?.length ?: 0f)).coerceAtLeast(1f)
                    fraction = (fraction + dy / range).coerceIn(0f, 1f)
                    indicator.dragFraction = fraction
                    val index = scrollerIndex(fraction, currentCount)
                    indicator.state.requestScrollToItem(index)
                    letter = currentSection(index)
                }
            })
        if (indicator.held && letter.isNotEmpty()) {
            val colors = MaterialTheme.colorScheme
            val bubble = with(density) { BUBBLE.toPx() }
            val gap = with(density) { (SCROLLER_TOUCH + 8.dp).toPx() }
            Box(Modifier.align(end)
                .offset {
                    val x = if (end == Alignment.TopStart) gap else -gap
                    IntOffset(x.roundToInt(), scrollerOffset(center(height) ?: 0f, bubble, height).roundToInt())
                }
                .size(BUBBLE).clip(CircleShape).background(colors.primaryContainer)
                .clearAndSetSemantics {},
                contentAlignment = Alignment.Center) {
                Text(letter, style = MaterialTheme.typography.headlineSmall, color = colors.onPrimaryContainer)
            }
        }
    }
}
