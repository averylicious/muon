package dev.avery.muon

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.DpSize
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
    val labelWidth = volumeBubbleMargin() * 2
    val enabled = !state.fixed && state.maximum > state.minimum
    // The percentage is feedback for a finger on the slider, not a permanent label (#117).
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val showBubble = !state.fixed && (dragged || pressed)

    // material3 1.5 keeps custom thumbs and tracks only on the SliderState overload. The state is the
    // system volume's mirror, set on every composition, as the plain-value overload itself does.
    // No steps: a tick per device volume step drew a dotted ruler along the track (#117). The
    // value still lands on whole steps, because it is rounded before it is set.
    val range = state.minimum.toFloat()..maxOf(state.maximum, state.minimum + 1).toFloat()
    val slider = remember(range) { SliderState(state.current.toFloat(), trackRange = range) }
    slider.value = state.current.toFloat()
    Slider(
        state = slider,
        onValueChange = { setVolume(it.roundToInt()) },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth()
            // The bubble floats above the track only while dragging, so no room is held for it; the
            // caller still leaves its horizontal overhang around the row. The line marks the track.
            .layout { measurable, constraints ->
                val slider = measurable.measure(constraints)
                layout(slider.width, slider.height, mapOf(VolumeTrackCenter to slider.height / 2)) {
                    slider.place(0, 0)
                }
            }
            .semantics { contentDescription = "Media volume level" },
        thumb = {
            Layout(content = {
                // Slimmer than the seek bar's, so volume reads as the secondary control it is.
                SliderDefaults.Thumb(interactionSource = interaction, enabled = enabled,
                    thumbSize = DpSize(4.dp, VolumeThumbHeight))
                if (showBubble) Surface(
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
        track = { sliderState ->
            SliderDefaults.Track(sliderState, Modifier.height(VolumeTrackHeight), enabled = enabled)
        },
    )
}

/** The volume track and thumb, smaller than the seek bar's 16 dp track and 44 dp thumb (#117). */
private val VolumeTrackHeight = 10.dp
private val VolumeThumbHeight = 28.dp
