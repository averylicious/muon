package dev.avery.muon

import android.annotation.SuppressLint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Android 17 QPR2 (API 37.2) lets a RenderNode blur whatever its window drew behind it, live
 * (`setBackdropRenderEffect`). `SDK_INT_FULL` only exists from Android 16, so it is read behind that check.
 */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.BAKLAVA)
internal val backdropOnThisPhone: Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2

/** What a small floating surface (the page toolbar, the player's notices, the A–Z bubble) is made of. */
internal enum class GlassLook { Glass, Tinted, Solid }

/**
 * Frosted needs the backdrop: these surfaces float over content Frost records or never sees, so Frost
 * can't draw behind them. Without the backdrop, Frosted keeps them solid, as they always were.
 */
internal fun glassLook(surface: BarSurface, backdrop: Boolean): GlassLook = when (surface) {
    BarSurface.Frosted -> if (backdrop) GlassLook.Glass else GlassLook.Solid
    BarSurface.Tinted -> GlassLook.Tinted
    BarSurface.Solid -> GlassLook.Solid
}

/**
 * Blurs what Muon's window drew behind this element by [blur], inside [shape], on Android 17 QPR2, and
 * scales its colour by [saturation] (1 leaves it as it is); elsewhere, or with [enabled] false, it adds
 * nothing. Put it before the element's own background, which then tints the blur. A gradient [blur]
 * needs Android 13, which every 37.2 phone has.
 *
 * The backdrop only sees this window, so it does nothing inside a dialog, sheet or menu, and an ancestor
 * drawn offscreen (a fade in progress, the library blurred behind the player) blanks it while that lasts.
 * It re-blurs every frame, so keep it to small surfaces.
 */
internal fun Modifier.backdropBlur(blur: BlurRadiusSpec, shape: Shape, enabled: Boolean = true, saturation: Float = 1f): Modifier =
    if (!enabled || !backdropOnThisPhone) this else drawWithCache {
        val backdrop = Backdrop(blur.createRenderEffect(size, this).asAndroidRenderEffect(), saturation)
        val outline = shape.createOutline(size, layoutDirection, this)
        val edge = Path().apply { addOutline(outline) }
        onDrawBehind { backdrop.draw(this, outline, edge) }
    }

// The backdrop effect is 37.2, a minor release lint can't tell from 36; only backdropBlur builds this,
// behind backdropOnThisPhone's SDK_INT_FULL check.
@SuppressLint("NewApi")
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private class Backdrop(blur: RenderEffect, saturation: Float) {
    private val node = RenderNode("backdrop").apply {
        setBackdropRenderEffect(if (saturation == 1f) blur else RenderEffect.createChainEffect(
            RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })), blur))
        setClipToOutline(true)
    }
    private val clip = android.graphics.Outline()

    fun draw(scope: DrawScope, outline: Outline, edge: Path) {
        node.setPosition(0, 0, scope.size.width.toInt(), scope.size.height.toInt())
        when (outline) {
            is Outline.Rectangle -> clip.setRect(0, 0, scope.size.width.toInt(), scope.size.height.toInt())
            is Outline.Rounded -> outline.roundRect.let {
                clip.setRoundRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt(), it.topLeftCornerRadius.x)
            }
            is Outline.Generic -> clip.setPath(outline.path.asAndroidPath())
        }
        node.setOutline(clip)
        // One recorded op, so the node is not dropped as empty before its backdrop is drawn.
        node.beginRecording().drawColor(android.graphics.Color.TRANSPARENT)
        node.endRecording()
        // The node's own outline clip did not hold the blurred backdrop on the Pixel (.1802): it spread in
        // a full-width band around a notice. Clipping where it is drawn keeps it to the shape.
        scope.clipPath(edge) { drawContext.canvas.nativeCanvas.drawRenderNode(node) }
    }
}

/**
 * A see-through notice's tints. It takes the surface's own colours rather than Material's inverse ones:
 * the inverse is pale in dark mode, and at these tints it turned into a murky grey slab there (.1802).
 */
private const val NOTICE_GLASS_TINT = 0.78f
private val NOTICE_BLUR = 22.dp
private val NOTICE_SHAPE = RoundedCornerShape(16.dp)

/**
 * A notice in Bar surface's material ([glassLook]): glass over the backdrop on 37.2 when Frosted,
 * see-through when Tinted, Material's own snackbar when Solid. The see-through ones are drawn here,
 * because Material's always casts a shadow, which showed through as a darker box inside it (.1802).
 */
@Composable
internal fun SurfaceSnackbar(data: SnackbarData, surface: BarSurface) {
    val look = glassLook(surface, backdropOnThisPhone)
    if (look == GlassLook.Solid) { Snackbar(data); return }
    val colors = MaterialTheme.colorScheme
    val tint = if (look == GlassLook.Glass) NOTICE_GLASS_TINT else TINTED_TINT
    Surface(shape = NOTICE_SHAPE, color = colors.surfaceContainerHighest.copy(alpha = tint), contentColor = colors.onSurface,
        border = BorderStroke(0.5.dp, colors.onSurface.copy(alpha = 0.12f)),
        modifier = Modifier.padding(12.dp)
            .backdropBlur(BlurRadiusSpec.uniform(NOTICE_BLUR), NOTICE_SHAPE, enabled = look == GlassLook.Glass)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction, colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)) { Text(label) }
            }
            if (data.visuals.withDismissAction) IconButton(onClick = data::dismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss" }) { MuonIcon("close") }
        }
    }
}
