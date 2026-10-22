package dev.avery.muon

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Motion pass 2: the library's pages share their pictures. The album cover a tile shows grows into
 * the album page's cover, and an artist's circle into their page's, instead of one page fading over
 * the other. Provided by the Library tab around its pages; anywhere else it is absent and nothing is
 * shared.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class SharedMotion(val transition: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

internal val LocalSharedMotion = compositionLocalOf<SharedMotion?> { null }

/** Tags a picture as the one element of [key] that moves between pages; nothing outside the library. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun sharedPicture(key: String): Modifier {
    val motion = LocalSharedMotion.current ?: return Modifier
    return with(motion.transition) {
        Modifier.sharedElement(rememberSharedContentState(key), motion.visibility)
    }
}

internal fun albumPictureKey(albumKey: String) = "picture:$albumKey"
internal fun artistPictureKey(artistKey: String) = "picture:$artistKey"

/**
 * A tap target that gives a little under the finger and springs back when let go, with the usual
 * ripple: the tiles feel pressed rather than only lit.
 */
@Composable
internal fun springyClick(label: String, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit): Modifier {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    return Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(shape)
        .clickable(interactionSource = press, indication = LocalIndication.current, onClickLabel = label, onClick = onClick)
}

/**
 * The play button's shape: round while paused, easing into a rounded square while playing, the way
 * Material 3 Expressive marks the playing state. [size] is the button's side.
 */
@Composable
internal fun playButtonShape(playing: Boolean, size: Dp): RoundedCornerShape {
    val corner by animateDpAsState(if (playing) size * 0.3f else size / 2,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow), label = "play shape")
    return RoundedCornerShape(corner)
}
