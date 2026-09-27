package dev.avery.muon

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The side of the tiny copy of the cover that is blurred, then stretched over the whole player. */
private const val BACKDROP_SIDE = 40
/** The blur's reach, in pixels of that copy, and how many box passes approximate a Gaussian. */
private const val BACKDROP_RADIUS = 4
private const val BACKDROP_PASSES = 3

/**
 * Now Playing's glass (the Canary experiment): the song's own cover, blurred and stretched behind
 * the whole player, under a veil of the player's cover-tinted background so text keeps its contrast.
 * Each song glows in its own colours and shapes, as the system's panels do over the wallpaper.
 *
 * The blur is worked out once per song on a tiny copy of the cover, off the main thread, and drawn
 * stretched with filtering; nothing is blurred per frame, so it costs the same as a picture and works
 * on every Android version. Songs crossfade from one backdrop to the next.
 */
@Composable
internal fun CoverBackdrop(url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        // Kept while the next one is worked out, so a skip crossfades instead of flashing.
        if (url == null) { backdrop = null; return@LaunchedEffect }
        blurredCover(context, url)?.let { backdrop = it }
    }
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    Box(modifier.fillMaxSize().clearAndSetSemantics {}) {
        Crossfade(backdrop, animationSpec = motionMedium(), label = "cover backdrop") { image ->
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
        }
        // Strong enough that the controls read on any cover, light enough that its colours show.
        Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = if (dark) 0.62f else 0.7f)))
    }
}

private suspend fun blurredCover(context: android.content.Context, url: String): ImageBitmap? =
    withContext(Dispatchers.Default) {
        val cover = artworkBitmap(context, url) ?: return@withContext null
        runCatching {
            val small = Bitmap.createScaledBitmap(cover, BACKDROP_SIDE, BACKDROP_SIDE, true)
            val pixels = IntArray(BACKDROP_SIDE * BACKDROP_SIDE)
            small.getPixels(pixels, 0, BACKDROP_SIDE, 0, 0, BACKDROP_SIDE, BACKDROP_SIDE)
            val blurred = boxBlur(pixels, BACKDROP_SIDE, BACKDROP_SIDE, BACKDROP_RADIUS, BACKDROP_PASSES)
            Bitmap.createBitmap(blurred, BACKDROP_SIDE, BACKDROP_SIDE, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }

/**
 * Blurs opaque ARGB [pixels] ([width] × [height]) with [passes] of a separable box blur of [radius],
 * which together approach a Gaussian. Edges repeat their outermost pixel, so the border does not
 * darken. The result is opaque.
 */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
    var current = pixels.copyOf()
    if (radius <= 0 || width <= 0 || height <= 0) return current
    val scratch = IntArray(current.size)
    repeat(passes) {
        blurLine(current, scratch, width, height, radius, horizontal = true)
        blurLine(scratch, current, width, height, radius, horizontal = false)
    }
    return current
}

private fun blurLine(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val span = radius * 2 + 1
    for (line in 0 until lines) {
        fun at(i: Int): Int {
            val c = i.coerceIn(0, length - 1)
            return if (horizontal) src[line * width + c] else src[c * width + line]
        }
        var r = 0; var g = 0; var b = 0
        for (i in -radius..radius) { val p = at(i); r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF }
        for (i in 0 until length) {
            val out = (0xFF shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
            if (horizontal) dst[line * width + i] = out else dst[i * width + line] = out
            val leaving = at(i - radius); val entering = at(i + radius + 1)
            r += (entering shr 16 and 0xFF) - (leaving shr 16 and 0xFF)
            g += (entering shr 8 and 0xFF) - (leaving shr 8 and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}

/** Whether the player should show [CoverBackdrop]: the blur switch is on, and the ground is not pure black. */
internal fun showCoverBackdrop(blur: Boolean, background: Color): Boolean = blur && background != Color.Black
