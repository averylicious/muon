package dev.avery.muon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Canary experiment's frosted glass: the mini player and the navigation bar show the tab under
 * them, blurred, through their own colours at [GLASS_TINT], as Android 17's shade and bars do. The tab
 * content records itself into a layer ([frostSource]); each bar draws that layer behind itself, moved
 * to where the bar sits over it and blurred ([frostedBehind]). Both are RenderNodes, so scrolling the
 * list re-records the content and the bars show it without being recorded again.
 */
@Stable
internal class Frost(val content: GraphicsLayer, val blurred: GraphicsLayer) {
    /** Where the content is, so a bar can find its own place over it. Read in the draw phase only. */
    var source: LayoutCoordinates? = null
    /** Where the bars are. Kept here rather than in the modifier, which a recomposition replaces. */
    var bars: LayoutCoordinates? = null
}

@Composable
internal fun rememberFrost(): Frost {
    val content = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(content, blurred) { Frost(content, blurred) }
}

/**
 * Whether the bars are glass: only with the experiment's blur on, on Android 12 or newer (where
 * `RenderEffect` exists), and with the bars along the bottom. Sideways, the tabs are a rail and the
 * player a side panel, so there is nothing for content to scroll under.
 */
internal fun frostedGlassOn(blur: Boolean, sdk: Int, sideways: Boolean): Boolean = blur && sdk >= 31 && !sideways

/** How much of the bars' own colour lies over the blur: enough to read them, not so much it looks solid. */
internal const val GLASS_TINT = 0.8f

/** How strongly the content blurs under the bars. */
internal val GLASS_BLUR: Dp = 24.dp

/**
 * The room at the bottom a scrolling list leaves for the bars when they are glass, so its last row can
 * scroll clear of them; zero with solid bars, whose space the scaffold already keeps free.
 */
internal val LocalUnderBars = compositionLocalOf { 0.dp }

/** Records everything this content draws, for the bars to show; the content is drawn as before. */
internal fun Modifier.frostSource(frost: Frost?): Modifier = if (frost == null) this else this
    .onGloballyPositioned { frost.source = it }
    .drawWithContent {
        frost.content.record { this@drawWithContent.drawContent() }
        drawLayer(frost.content)
    }

/**
 * Draws the recorded content behind this bar, where the bar sits over it, blurred by [radius]. Nothing
 * is drawn until both have been placed, so a first frame is simply the bar's own colour.
 */
internal fun Modifier.frostedBehind(frost: Frost?, radius: Dp = GLASS_BLUR): Modifier {
    if (frost == null) return this
    return this.onGloballyPositioned { frost.bars = it }.drawBehind {
        val source = frost.source ?: return@drawBehind
        val here = frost.bars ?: return@drawBehind
        if (!source.isAttached || !here.isAttached) return@drawBehind
        val at = source.localPositionOf(here, Offset.Zero)
        val px = radius.toPx()
        frost.blurred.renderEffect = BlurEffect(px, px, TileMode.Clamp)
        frost.blurred.record { translate(-at.x, -at.y) { drawLayer(frost.content) } }
        drawLayer(frost.blurred)
    }
}
