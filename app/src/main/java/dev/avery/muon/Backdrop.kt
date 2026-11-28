package dev.avery.muon

import android.annotation.SuppressLint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp

/**
 * Android 17 QPR2 (API 37.2) lets a RenderNode blur whatever its window drew behind it, live
 * (`setBackdropRenderEffect`). `SDK_INT_FULL` only exists from Android 16, so it is read behind that check.
 */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.BAKLAVA)
internal val backdropOnThisPhone: Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2

/** What the floating page toolbar is made of, for an [effectiveBarSurface]. */
internal enum class ToolbarLook { Glass, Tinted, Solid }

/**
 * Frosted needs the backdrop: the toolbar floats over the very list Frost records, so Frost can't draw
 * behind it. Without the backdrop, Frosted keeps today's solid toolbar.
 */
internal fun toolbarLook(surface: BarSurface, backdrop: Boolean): ToolbarLook = when (surface) {
    BarSurface.Frosted -> if (backdrop) ToolbarLook.Glass else ToolbarLook.Solid
    BarSurface.Tinted -> ToolbarLook.Tinted
    BarSurface.Solid -> ToolbarLook.Solid
}

/**
 * Blurs what Muon's window drew behind this element by [radius], inside [shape], on Android 17 QPR2;
 * elsewhere, or with [enabled] false, it adds nothing. Put it before the element's own background, which
 * then tints the blur.
 *
 * The backdrop only sees this window, so it does nothing inside a dialog, sheet or menu, and an ancestor
 * drawn offscreen (a fade in progress, the library blurred behind the player) blanks it while that lasts.
 * It re-blurs every frame, so keep it to small surfaces.
 */
internal fun Modifier.backdropBlur(radius: Dp, shape: Shape, enabled: Boolean = true): Modifier =
    if (!enabled || !backdropOnThisPhone) this else drawWithCache {
        val backdrop = Backdrop(radius.toPx())
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { backdrop.draw(this, outline) }
    }

// The backdrop effect is 37.2, a minor release lint can't tell from 36; only backdropBlur builds this,
// behind backdropOnThisPhone's SDK_INT_FULL check.
@SuppressLint("NewApi")
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
private class Backdrop(radius: Float) {
    private val node = RenderNode("backdrop").apply {
        setBackdropRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        setClipToOutline(true)
    }
    private val clip = android.graphics.Outline()

    fun draw(scope: DrawScope, outline: Outline) {
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
        scope.drawContext.canvas.nativeCanvas.drawRenderNode(node)
    }
}
