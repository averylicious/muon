package dev.avery.muon

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt

/** Space around the floating capsules at the sides and bottom, and between the mini player and the tabs. */
internal val FLOAT_MARGIN: Dp = 12.dp
internal val FLOAT_GAP: Dp = 8.dp

internal val CAPSULE = RoundedCornerShape(50)

/**
 * The capsules' hairline edge. With no edge of the screen behind them, a see-through capsule over
 * rows of the same colour had nothing to tell where it ends.
 */
@Composable
internal fun capsuleEdge(): BorderStroke = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

/**
 * Inside the capsule, the mini player's progress line keeps this far from each end: at the capsule's
 * height its rounded ends would otherwise cut the line off.
 */
internal val FLOAT_PROGRESS_INSET: Dp = 36.dp

private val TABS_PADDING = 6.dp

/** A tucked tab is this much shorter than the chosen height, but never shorter than the smallest choice. */
private val TUCK_SHRINK = 8.dp

private fun tuckedTabHeight(tab: Dp): Dp = maxOf(tab - TUCK_SHRINK, FLOAT_TAB_HEIGHT_MIN.dp)

/** Without Tuck in, a list scrolled down narrows the tabs capsule to this share of the width as its labels go. */
private const val TUCKED_TABS_SHARE = 0.62f

/** Below this much scrolling in one step, the bar stays as it is, so a resting finger's jitter does nothing. */
private const val TUCK_SLOP = 1f

/**
 * Whether the floating bar should be tucked after a list scrolled by [consumedY]: scrolling down
 * (content moving up, negative) tucks it, scrolling up brings it back, and anything smaller than
 * [TUCK_SLOP] leaves it as [tucked].
 */
internal fun tuckAfterScroll(tucked: Boolean, consumedY: Float): Boolean = when {
    consumedY < -TUCK_SLOP -> true
    consumedY > TUCK_SLOP -> false
    else -> tucked
}

/**
 * Floating navigation (Gen 4): the tabs in one capsule, with a droplet behind the [selected] one that
 * springs across to a newly chosen tab. [modifier] carries the capsule's glass ([frostedBehind]).
 * [tuck] (0 to 1) shortens the tabs and fades their labels away; [only] (0 to 1) also folds every tab
 * but the selected one away, for Tuck in. Folded tabs keep their semantics, so TalkBack can still reach them.
 * Tapped while mostly folded, the one tab showing calls [expand] instead: it is already selected, so
 * selecting it again did nothing (the user's QA of .1785).
 */
@Composable
internal fun FloatingTabs(labels: List<String>, selected: Int, color: Color, select: (Int) -> Unit,
    modifier: Modifier = Modifier, height: Dp = FLOAT_TAB_HEIGHT_DEFAULT.dp, tuck: Float = 0f, only: Float = 0f,
    expand: () -> Unit = {}, icon: @Composable (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tabHeight = lerp(height, tuckedTabHeight(height), tuck)
    // A weight must stay above zero.
    val folded = (1f - only).coerceAtLeast(0.001f)
    Surface(color = color, shape = CAPSULE, border = capsuleEdge(), modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(TABS_PADDING)) {
            val width = maxWidth / labels.size
            val at by animateDpAsState(width * selected, motionSpatial(), label = "droplet")
            // Its cell's width only while the tabs share the capsule evenly; it fades as they fold.
            Box(Modifier.offset(x = at).width(width).height(tabHeight).graphicsLayer { alpha = 1f - only }
                .background(colors.primary.copy(alpha = 0.16f), CAPSULE))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val chosen = index == selected
                    Column(Modifier.weight(if (chosen) 1f else folded).height(tabHeight)
                        .graphicsLayer { alpha = if (chosen) 1f else 1f - only }.clip(CAPSULE)
                        .selectable(selected = chosen, role = Role.Tab) { if (only > 0.5f) expand() else select(index) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CompositionLocalProvider(LocalContentColor provides if (chosen) colors.onSurface else colors.onSurfaceVariant) {
                            icon(index)
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false,
                                modifier = Modifier.graphicsLayer { alpha = 1f - tuck }.layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(placeable.width, (placeable.height * (1f - tuck)).roundToInt()) { placeable.place(0, 0) }
                                })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The floating capsules, placed by how far they have tucked ([tuck], 0 to 1, read while laying out):
 * without [tuckIn] the tabs narrow and lose their labels under the mini player; with it, the tabs
 * shrink to the current one and the mini player slides down beside them into one row.
 * Its height stays the untucked stack's, so the lists' end padding (LocalUnderBars) never moves while
 * it tucks; the room left above a tucked row has nothing to touch, so taps there reach the list.
 * The capsules take [widthShare] of the width, centred, and the tabs are [tabHeight] tall untucked.
 */
@Composable
internal fun FloatingStack(tuck: () -> Float, tuckIn: Boolean, modifier: Modifier = Modifier,
    widthShare: Float = 1f, tabHeight: Dp = FLOAT_TAB_HEIGHT_DEFAULT.dp,
    mini: @Composable () -> Unit, tabs: @Composable () -> Unit) {
    Layout({
        Box(Modifier.layoutId(MINI)) { mini() }
        Box(Modifier.layoutId(TABS)) { tabs() }
    }, modifier) { measurables, constraints ->
        val full = constraints.maxWidth
        val width = (full * widthShare).roundToInt()
        val side = (full - width) / 2
        val t = tuck().coerceIn(0f, 1f)
        val gap = FLOAT_GAP.roundToPx()
        // Tucked in, the tabs capsule holds only the current tab's icon.
        val tuckedTabs = tuckedTabHeight(tabHeight) + TABS_PADDING * 2
        val narrowTabs = if (tuckIn) tuckedTabs.toPx() else width * TUCKED_TABS_SHARE
        val tabsWidth = androidx.compose.ui.util.lerp(width.toFloat(), narrowTabs, t).roundToInt()
        val tabsPlaced = measurables.first { it.layoutId == TABS }.measure(Constraints.fixedWidth(tabsWidth))
        val besideTabs = tuckedTabs.roundToPx() + gap
        val miniWidth = if (tuckIn) androidx.compose.ui.util.lerp(width, width - besideTabs, t) else width
        val miniPlaced = measurables.first { it.layoutId == MINI }.measure(Constraints.fixedWidth(miniWidth))
        val tabsRoom = maxOf(tabsPlaced.height, (tabHeight + TABS_PADDING * 2).roundToPx())
        val height = (if (miniPlaced.height > 0) miniPlaced.height + gap else 0) + tabsRoom
        layout(full, height) {
            val tabsY = height - tabsPlaced.height
            tabsPlaced.placeRelative(side + if (tuckIn) 0 else (width - tabsPlaced.width) / 2, tabsY)
            // Stacked, just above the tabs however short they have become; tucked in, centred on their row.
            val stackY = tabsY - gap - miniPlaced.height
            val rowY = tabsY + (tabsPlaced.height - miniPlaced.height) / 2
            miniPlaced.placeRelative(side + if (tuckIn) androidx.compose.ui.util.lerp(0, besideTabs, t) else 0,
                if (tuckIn) androidx.compose.ui.util.lerp(stackY, rowY, t) else stackY)
        }
    }
}

private const val MINI = "mini"
private const val TABS = "tabs"
