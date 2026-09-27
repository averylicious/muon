package dev.avery.muon

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Now Playing in the colours of the song's cover: its background, controls and sliders take their hue
 * from the artwork, so each song has its own mood. The seed is the cover's most prominent vivid hue
 * ([coverSeed]); the tones below are fixed per role, so text keeps its contrast whatever the cover.
 * A grey cover gives a near-neutral scheme. Pure black keeps its black surfaces and only tints the
 * accents. Changing song fades from one scheme to the next.
 */
@Composable
internal fun ArtworkTheme(item: MediaItem?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val url = item?.mediaMetadata?.artworkUri?.toString()
    val context = LocalContext.current
    // Kept while the next cover's colour is worked out, so a skip fades rather than flashing the default.
    var seed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(url) {
        if (url == null) { seed = null; return@LaunchedEffect }
        artworkSeed(context, url)?.let { seed = it }
    }
    val dark = base.background.luminance() < 0.5f
    val target = seed?.let { artworkScheme(base, it, dark, pureBlack = base.background == Color.Black) } ?: base
    MaterialTheme(colorScheme = animated(target), typography = MaterialTheme.typography, content = content)
}

/** The cover's seed colour, or null if it cannot be read. Off the main thread. */
private suspend fun artworkSeed(context: android.content.Context, url: String): Int? = withContext(Dispatchers.Default) {
    val bitmap = artworkBitmap(context, url) ?: return@withContext null
    runCatching {
        val step = maxOf(1, minOf(bitmap.width, bitmap.height) / 48)
        val pixels = ArrayList<Int>()
        for (y in 0 until bitmap.height step step) for (x in 0 until bitmap.width step step) pixels += bitmap.getPixel(x, y)
        coverSeed(pixels)
    }.getOrNull()
}

/**
 * The seed colour of a cover's sampled [pixels] (ARGB): the hue that covers the most vivid area, in
 * 24 buckets of 15°, each pixel counting by its saturation so a small bright accent can outweigh a
 * large grey field. A cover with nothing vivid gives its average colour, and so a near-neutral scheme.
 * The result has that bucket's average hue and saturation at mid lightness; only hue and saturation
 * are used.
 */
internal fun coverSeed(pixels: List<Int>): Int? {
    if (pixels.isEmpty()) return null
    val weight = FloatArray(24)
    val sin = FloatArray(24)
    val cos = FloatArray(24)
    val sat = FloatArray(24)
    var r = 0L; var g = 0L; var b = 0L
    for (p in pixels) {
        r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
        val (hue, s) = hueAndSaturation(p)
        val light = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 765f
        // Near black and near white carry no usable hue, whatever their nominal saturation.
        if (s < 0.25f || light < 0.08f || light > 0.94f) continue
        val bucket = (hue / 15f).toInt().coerceIn(0, 23)
        weight[bucket] += s
        val radians = Math.toRadians(hue.toDouble())
        sin[bucket] += (kotlin.math.sin(radians) * s).toFloat()
        cos[bucket] += (kotlin.math.cos(radians) * s).toFloat()
        sat[bucket] += s * s
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return null
    // Too little vivid colour to lead: the cover's average, which reads as its overall cast.
    if (weight[best] < pixels.size * 0.02f) {
        val n = pixels.size
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }
    val hue = Math.toDegrees(kotlin.math.atan2(sin[best].toDouble(), cos[best].toDouble())).toFloat().mod(360f)
    return hsl(hue, sat[best] / weight[best], 0.5f)
}

@Composable
private fun animated(target: ColorScheme): ColorScheme {
    @Composable fun a(color: Color) = animateColorAsState(color, motionMedium(), label = "artwork colour").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        secondary = a(target.secondary), onSecondary = a(target.onSecondary),
        secondaryContainer = a(target.secondaryContainer), onSecondaryContainer = a(target.onSecondaryContainer),
        background = a(target.background), onBackground = a(target.onBackground),
        surface = a(target.surface), onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant), onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceContainerHigh = a(target.surfaceContainerHigh), surfaceContainerHighest = a(target.surfaceContainerHighest),
        outline = a(target.outline), outlineVariant = a(target.outlineVariant),
    )
}

/** [base] with its accents and, unless [pureBlack], its surfaces in the hue of [seed] (ARGB). */
internal fun artworkScheme(base: ColorScheme, seed: Int, dark: Boolean, pureBlack: Boolean): ColorScheme {
    val (hue, saturation) = hueAndSaturation(seed)
    fun tone(sat: Float, light: Float) = Color(hsl(hue, minOf(saturation, sat), light))
    val accents = if (dark) base.copy(
        primary = tone(0.60f, 0.78f), onPrimary = tone(0.60f, 0.16f),
        primaryContainer = tone(0.50f, 0.26f), onPrimaryContainer = tone(0.50f, 0.90f),
        secondary = tone(0.30f, 0.76f), onSecondary = tone(0.30f, 0.16f),
        secondaryContainer = tone(0.30f, 0.26f), onSecondaryContainer = tone(0.30f, 0.90f),
    ) else base.copy(
        primary = tone(0.60f, 0.34f), onPrimary = Color.White,
        primaryContainer = tone(0.50f, 0.86f), onPrimaryContainer = tone(0.60f, 0.14f),
        secondary = tone(0.30f, 0.38f), onSecondary = Color.White,
        secondaryContainer = tone(0.40f, 0.87f), onSecondaryContainer = tone(0.40f, 0.14f),
    )
    if (pureBlack) return accents
    return if (dark) accents.copy(
        background = tone(0.30f, 0.08f), onBackground = tone(0.10f, 0.92f),
        surface = tone(0.30f, 0.08f), onSurface = tone(0.10f, 0.92f),
        surfaceVariant = tone(0.20f, 0.20f), onSurfaceVariant = tone(0.12f, 0.76f),
        surfaceContainerHigh = tone(0.20f, 0.15f), surfaceContainerHighest = tone(0.20f, 0.19f),
        outline = tone(0.12f, 0.55f), outlineVariant = tone(0.15f, 0.30f),
    ) else accents.copy(
        background = tone(0.35f, 0.95f), onBackground = tone(0.15f, 0.10f),
        surface = tone(0.35f, 0.95f), onSurface = tone(0.15f, 0.10f),
        surfaceVariant = tone(0.30f, 0.89f), onSurfaceVariant = tone(0.12f, 0.30f),
        surfaceContainerHigh = tone(0.30f, 0.90f), surfaceContainerHighest = tone(0.30f, 0.87f),
        outline = tone(0.12f, 0.48f), outlineVariant = tone(0.15f, 0.80f),
    )
}

/** Hue in degrees and HSL saturation of an ARGB colour. */
internal fun hueAndSaturation(argb: Int): Pair<Float, Float> {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val light = (max + min) / 2f
    if (delta == 0f) return 0f to 0f
    val saturation = delta / (1f - abs(2f * light - 1f))
    val hue = when (max) {
        r -> 60f * (((g - b) / delta).mod(6f))
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return hue to saturation.coerceIn(0f, 1f)
}

/** An opaque ARGB colour from hue (degrees), saturation and lightness (0..1). */
internal fun hsl(hue: Float, saturation: Float, lightness: Float): Int {
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val x = c * (1f - abs((hue / 60f).mod(2f) - 1f))
    val m = lightness - c / 2f
    val (r, g, b) = when ((hue.mod(360f) / 60f).toInt()) {
        0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    fun channel(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
