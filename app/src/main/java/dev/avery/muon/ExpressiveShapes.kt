package dev.avery.muon

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph

/**
 * A shape part-way between two Material shapes: [progress] 0 is [morph]'s start, 1 its end. The
 * polygons are Material's normalized ones (a unit square), so the path is scaled to the size asked
 * for and centred in it; [rotation] turns it about that centre, in degrees.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal class MorphShape(private val morph: Morph, private val progress: Float,
    private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress, Path())
        path.transform(Matrix().apply { scale(size.width, size.height) })
        path.translate(size.center - path.getBounds().center)
        if (rotation != 0f) {
            val c = size.center
            path.translate(-c)
            path.transform(Matrix().apply { rotateZ(rotation) })
            path.translate(c)
        }
        return Outline.Generic(path)
    }
}

/**
 * The play button's shape. With [Expressive.motion] it morphs between two of Material 3 Expressive's
 * shapes: a nine-sided cookie while paused, which reads as "press to play", and a rounded square
 * while playing, turning a little on the way, with a spring that overshoots so the change has life.
 * Otherwise it is the round-to-rounded-square of motion pass 2. [size] is the button's side.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun playButtonShape(playing: Boolean, size: Dp): Shape {
    if (!Expressive.motion) {
        val corner by animateDpAsState(if (playing) size * 0.3f else size / 2,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "play shape")
        return RoundedCornerShape(corner)
    }
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Square) }
    // Bounded by the spring's overshoot, which Morph handles: a little past square and back.
    val progress by animateFloatAsState(if (playing) 1f else 0f,
        spring(dampingRatio = 0.6f, stiffness = 800f), label = "play morph")
    return MorphShape(morph, progress, rotation = progress * PLAY_MORPH_TURN)
}

/** How far the play button turns while it morphs, in degrees: one scallop of the nine-sided cookie. */
private const val PLAY_MORPH_TURN = 40f
