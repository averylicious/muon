package dev.avery.muon

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** Where each bar rests while paused, as a fraction of the height: an equalizer caught mid-beat. */
private val BAR_REST = floatArrayOf(0.45f, 0.8f, 0.6f)

/**
 * The heights each bar steps through while playing. Different lengths and speeds per bar, so the
 * three never fall into step and read as one block moving.
 */
private val BAR_BEATS = arrayOf(
    floatArrayOf(0.9f, 0.3f, 0.65f, 0.2f, 0.8f),
    floatArrayOf(0.35f, 1f, 0.5f, 0.85f),
    floatArrayOf(0.7f, 0.25f, 0.95f, 0.4f, 0.6f, 0.3f),
)
private val BAR_STEP_MS = intArrayOf(260, 330, 290)

/**
 * The now-playing marker (Material Symbols' *equalizer*, set in motion): three rounded bars that
 * rise and fall while [playing], and settle to rest when paused. Decorative; the row carrying it
 * says "Now playing" to TalkBack. With animations turned off in the system settings it holds still.
 */
@Composable
internal fun NowPlayingBars(playing: Boolean, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary) {
    val bars = remember { List(3) { Animatable(BAR_REST[it]) } }
    bars.forEachIndexed { i, bar ->
        LaunchedEffect(bar, playing) {
            val still = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f
            if (!playing || still) { bar.animateTo(BAR_REST[i], tween(300)); return@LaunchedEffect }
            val beats = BAR_BEATS[i]
            var step = 0
            while (true) {
                bar.animateTo(beats[step % beats.size], tween(BAR_STEP_MS[i], easing = FastOutSlowInEasing))
                step++
            }
        }
    }
    // Drawn rather than laid out, so a beat only redraws and never measures the row again.
    Canvas(modifier.size(18.dp, 16.dp).clearAndSetSemantics {}) {
        val width = size.width / 4.5f
        val gap = (size.width - width * 3) / 2
        bars.forEachIndexed { i, bar ->
            val height = size.height * bar.value.coerceIn(0.15f, 1f)
            drawRoundRect(color, Offset(i * (width + gap), size.height - height), Size(width, height),
                CornerRadius(width / 2))
        }
    }
}
