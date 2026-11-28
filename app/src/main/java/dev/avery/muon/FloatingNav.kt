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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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

private val TAB_HEIGHT = 52.dp
private val TABS_PADDING = 6.dp

/**
 * Floating navigation (Gen 4): the tabs in one capsule, with a droplet behind the [selected] one that
 * springs across to a newly chosen tab. [modifier] carries the capsule's glass ([frostedBehind]).
 */
@Composable
internal fun FloatingTabs(labels: List<String>, selected: Int, color: Color, select: (Int) -> Unit,
    modifier: Modifier = Modifier, icon: @Composable (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = color, shape = CAPSULE, border = capsuleEdge(), modifier = modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.padding(TABS_PADDING)) {
            val width = maxWidth / labels.size
            val at by animateDpAsState(width * selected, motionSpatial(), label = "droplet")
            Box(Modifier.offset(x = at).width(width).height(TAB_HEIGHT).background(colors.primary.copy(alpha = 0.16f), CAPSULE))
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                labels.forEachIndexed { index, label ->
                    val chosen = index == selected
                    Column(Modifier.weight(1f).height(TAB_HEIGHT).clip(CAPSULE)
                        .selectable(selected = chosen, role = Role.Tab) { select(index) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        CompositionLocalProvider(LocalContentColor provides if (chosen) colors.onSurface else colors.onSurfaceVariant) {
                            icon(index)
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
