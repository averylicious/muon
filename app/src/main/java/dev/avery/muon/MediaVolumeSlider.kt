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
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the volume track's centre sits inside this control.
 *
 * The slider reserves space above itself for the floating percentage, so the middle of the whole
 * control is not the middle of the track. Anything that should line up with the track — the speaker
 * icons beside it — aligns to this line instead, which keeps them right at any font scale and
 * whether or not the bubble is reserved.
 */
internal val VolumeTrackCenter = HorizontalAlignmentLine(::min)

/**
 * How far the floating percentage reaches beyond each end of the track.
 *
 * At either extreme the bubble is centred on a thumb sitting at the very end of the track, so half
 * of it hangs past that end. Whoever lays the slider out has to leave that much room somewhere —
 * but it need not be between the track and what sits beside it, because the bubble floats above
 * the track line rather than beside it.
 */
@Composable
internal fun volumeBubbleMargin(): Dp {
    val label = rememberTextMeasurer().measure("100%", style = MaterialTheme.typography.labelLarge).size
    return (with(LocalDensity.current) { label.width.toDp() } + BubblePadding * 2) / 2
}

/** The bubble's own horizontal padding, on each side of its text. */
private val BubblePadding = 10.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaVolumeSlider(state: MediaVolumeState, setVolume: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val labelStyle = MaterialTheme.typography.labelLarge
    val labelSize = rememberTextMeasurer().measure("100%", style = labelStyle).size
    val density = LocalDensity.current
    val labelWidth = volumeBubbleMargin() * 2
    val labelHeight = with(density) { labelSize.height.toDp() } + 8.dp
    val enabled = !state.fixed && state.maximum > state.minimum

    Slider(
        value = state.current.toFloat(),
        onValueChange = { setVolume(it.roundToInt()) },
        valueRange = state.minimum.toFloat()..maxOf(state.maximum, state.minimum + 1).toFloat(),
        steps = (state.maximum - state.minimum - 1).coerceAtLeast(0),
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth()
            // The bubble's room is reserved by the caller, around the whole row, so the track can
            // run right up to whatever sits beside it.
            .padding(top = if (state.fixed) 0.dp else labelHeight + 8.dp)
            // Measured inside that reserved space, so the line lands on the track itself; the
            // padding above offsets it as it propagates out to whoever is aligning with it.
            .layout { measurable, constraints ->
                val slider = measurable.measure(constraints)
                layout(slider.width, slider.height, mapOf(VolumeTrackCenter to slider.height / 2)) {
                    slider.place(0, 0)
                }
            }
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
                    Text("${state.percent}%", Modifier.padding(horizontal = BubblePadding, vertical = 4.dp),
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
