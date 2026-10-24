package dev.avery.muon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** One wave's length along the bar, its height either side of the middle, and how fast it rolls. */
private val WAVE_LENGTH = 36.dp
private val WAVE_HEIGHT = 3.dp
private const val WAVE_PERIOD_MS = 1600f
private val TRACK_STROKE = 4.dp
/** The space either side of the thumb, which stands clear of both parts of the track. */
private val THUMB_GAP = 6.dp

/** The wave's height at [x] along the bar, for a wave rolled on by [phase] (0 to 1) and at [amplitude]. */
internal fun seekWaveY(x: Float, wavelength: Float, phase: Float, amplitude: Float): Float =
    amplitude * sin(2f * PI.toFloat() * (x / wavelength - phase))

/**
 * The seek bar's track as Material 3 Expressive's wavy progress, retried on the user's request
 * (2026-10-24; dropped on #40 under material3 1.4.0): the played part is a wave, rolling while the song
 * plays and smoothing out flat when it pauses or the thumb is held; the rest is a flat line. Only the
 * drawing changes: the stock [androidx.compose.material3.Slider] still takes the drag, the taps and
 * TalkBack's seek actions. The wave is read in the draw phase, so rolling it redraws the track alone.
 */
@Composable
internal fun WavySeekTrack(state: SliderState, moving: Boolean, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    val active = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f)
    val inactive = if (enabled) colors.secondaryContainer else colors.onSurface.copy(alpha = 0.12f)
    val amplitude by animateFloatAsState(if (moving) 1f else 0f, motionMedium(), label = "wave height")
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val start = withFrameMillis { it } - (phase.floatValue * WAVE_PERIOD_MS).toLong()
        while (true) withFrameMillis { now -> phase.floatValue = ((now - start) % WAVE_PERIOD_MS.toLong()) / WAVE_PERIOD_MS }
    }
    val path = remember { Path() }
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val stroke = TRACK_STROKE.toPx()
        val gap = THUMB_GAP.toPx()
        val mid = size.height / 2f
        val thumbX = state.coercedValueAsFraction * size.width
        fun x(at: Float) = if (rtl) size.width - at else at
        // The rest of the song: flat, after the gap past the thumb, with a dot marking its end.
        val restStart = (thumbX + gap + stroke / 2f).coerceAtMost(size.width)
        if (restStart < size.width - stroke / 2f) drawLine(inactive, Offset(x(restStart), mid), Offset(x(size.width - stroke / 2f), mid),
            strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(active, radius = stroke / 2f, center = Offset(x(size.width - stroke / 2f), mid))
        // What has played: the wave, up to the gap before the thumb.
        val end = thumbX - gap - stroke / 2f
        if (end > stroke / 2f) {
            val wavelength = WAVE_LENGTH.toPx()
            val height = WAVE_HEIGHT.toPx() * amplitude
            val p = phase.floatValue
            path.reset()
            var at = stroke / 2f
            path.moveTo(x(at), mid + seekWaveY(at, wavelength, p, height))
            val step = 2f
            while (at < end) {
                at = minOf(at + step, end)
                path.lineTo(x(at), mid + seekWaveY(at, wavelength, p, height))
            }
            drawPath(path, active, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}
