package dev.avery.muon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaVolumeSlider(state: MediaVolumeState, setVolume: (Int) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val labelStyle = MaterialTheme.typography.labelLarge
    val labelSize = rememberTextMeasurer().measure("100%", style = labelStyle).size
    val density = LocalDensity.current
    val labelWidth = with(density) { labelSize.width.toDp() } + 20.dp
    val labelHeight = with(density) { labelSize.height.toDp() } + 8.dp
    val enabled = !state.fixed && state.maximum > state.minimum

    Slider(
        value = state.current.toFloat(),
        onValueChange = { setVolume(it.roundToInt()) },
        valueRange = state.minimum.toFloat()..maxOf(state.maximum, state.minimum + 1).toFloat(),
        steps = (state.maximum - state.minimum - 1).coerceAtLeast(0),
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth()
            // Reserve room for the bubble at both endpoints and at larger font scales.
            .padding(horizontal = labelWidth / 2, vertical = 0.dp)
            .padding(top = if (state.fixed) 0.dp else labelHeight + 8.dp)
            .semantics { contentDescription = "Media volume level" },
        thumb = {
            Layout(content = {
                SliderDefaults.Thumb(interactionSource = interaction, enabled = enabled)
                if (!state.fixed) Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(labelWidth).clearAndSetSemantics { },
                ) {
                    Text("${state.percent}%", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = labelStyle, textAlign = TextAlign.Center, maxLines = 1)
                }
            }) { measurables, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val thumb = measurables[0].measure(loose)
                val bubble = measurables.getOrNull(1)?.measure(loose)
                // Only the real thumb determines track geometry. Its label is an overlay.
                layout(thumb.width, thumb.height) {
                    thumb.placeRelative(0, 0)
                    bubble?.placeRelative((thumb.width - bubble.width) / 2,
                        -bubble.height - 8.dp.roundToPx())
                }
            }
        },
    )
}
