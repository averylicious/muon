package dev.avery.muon

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How strongly the library blurs under a fully open player, the Canary experiment's take on the blur
 * Android 17 puts behind its shade and dialogs. The dimming stays on top of it, so text under the
 * player still loses contrast as it did before.
 */
internal val BLUR_BEHIND_RADIUS: Dp = 28.dp

/** How much of the blur to draw: as much as the player covers, or the song menu's own amount. */
internal fun blurBehindAmount(sheetPosition: Float, menu: Float): Float =
    maxOf(1f - sheetPosition.coerceIn(0f, 1f), menu.coerceIn(0f, 1f))

/**
 * Blurs what sits under the player in proportion to how far the player has risen, so a drag or a
 * preview from the mini player blurs the library as it goes, and [menu] (0 to 1) blurs it behind the
 * song actions sheet. Both are read in the draw phase, never in composition, so a moving sheet only
 * redraws the layer (#23). Android 12 is the first to apply a render effect; older phones, and
 * anyone with [Expressive.blur] off, keep the dimming alone.
 */
internal fun Modifier.blurBehind(sheet: PlayerSheet, menu: () -> Float): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) this else graphicsLayer {
        val amount = if (Expressive.blur) blurBehindAmount(sheet.position.value, menu()) else 0f
        val radius = amount * BLUR_BEHIND_RADIUS.toPx()
        // Under half a pixel the blur cannot be seen, and leaving it off spares the GPU its pass.
        renderEffect = if (radius < 0.5f) null else BlurEffect(radius, radius, TileMode.Clamp)
    }
