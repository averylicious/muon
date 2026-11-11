package dev.avery.muon

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Muon's motion, defined once. Durations are deliberately short: the point is to show that a
 * surface changed, not to make the user wait for it.
 */
internal const val MOTION_SHORT = 150
internal const val MOTION_MEDIUM = 250

/** Material's standard easing: quick to leave, gentle to arrive. */
internal val MotionEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Predictive Back's numbers, in one place. Two kinds of surface preview it, and they are not yet
 * the same on purpose: the open player ([Modifier.playerBackPreview]) shrinks by a fixed distance
 * and keeps Material's curve, while a Library page, On this device, Queue and Lyrics ([peekCard])
 * shrink to a fraction, as Android's guidance draws a detached surface. Each keeps the look it had;
 * anything that brings them together starts here.
 *
 * The player's curve is Material's easing for a back gesture's own progress, which moves most at the
 * start so the surface answers the finger immediately. The card's is the guidance's: decelerate,
 * never linear.
 */
internal val PredictiveBackEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
internal val PageBackEasing: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

/** The open player: how far it shrinks and drifts, and its corners, at the end of the gesture. */
internal val PLAYER_BACK_SHRINK: Dp = 48.dp
internal val PLAYER_BACK_SLIDE: Dp = 24.dp
internal val PLAYER_BACK_CORNER: Dp = 28.dp

/** A card: the fraction it shrinks to, its corners, and the dimming of what is underneath. */
internal const val PAGE_BACK_SCALE = 0.9f
internal val PAGE_BACK_CORNER: Dp = 32.dp
internal const val PAGE_BACK_DIM = 0.22f

/**
 * How far a previewing surface follows the finger up or down, from −1 (up) to 1 (down) of whatever
 * distance it is allowed: the finger's vertical travel since the gesture's first event, [drag],
 * counted over half the surface's [height] and decelerating, so the surface answers a small movement
 * and gives less and less to a large one. A Back with no finger behind it reports no position, and
 * an unmeasured surface has no height: both follow nothing.
 */
internal fun backFollow(drag: Float, height: Float): Float {
    if (drag.isNaN() || height.isNaN() || height <= 0f) return 0f
    val left = 1f - (abs(drag) / (height / 2f)).coerceIn(0f, 1f)
    val eased = 1f - left * left
    return if (drag < 0f) -eased else eased
}

/**
 * For what fades or changes colour. With [Expressive.motion] these are Material 3 Expressive's
 * effects springs (fast and default), which never overshoot; otherwise the eased tweens above.
 */
internal fun <T> motionShort(): FiniteAnimationSpec<T> =
    if (Expressive.motion) spring(dampingRatio = 1f, stiffness = 3800f) else tween(MOTION_SHORT, easing = MotionEasing)
internal fun <T> motionMedium(): FiniteAnimationSpec<T> =
    if (Expressive.motion) spring(dampingRatio = 1f, stiffness = 1600f) else tween(MOTION_MEDIUM, easing = MotionEasing)

/**
 * For what moves or changes size: Material 3 Expressive's default spatial spring, which overshoots a
 * little and settles, so a surface arrives with some life. Without [Expressive.motion], [motionMedium].
 * The values are material3 1.5.0-alpha29's `ExpressiveMotionTokens`, which are internal there.
 */
internal fun <T> motionSpatial(): FiniteAnimationSpec<T> =
    if (Expressive.motion) spring(dampingRatio = 0.8f, stiffness = 380f) else tween(MOTION_MEDIUM, easing = MotionEasing)

/**
 * The same spring without the overshoot, for motion that must not pass its target: a surface that
 * covers the whole screen, whose far edge would lift off and show a strip of what it covers, and any
 * size, padding or progress that settles at zero, where an overshoot goes negative. Negative padding
 * throws, which crashed Search as its bar widened (canary.297).
 */
internal fun <T> motionSpatialFull(): FiniteAnimationSpec<T> =
    if (Expressive.motion) spring(dampingRatio = 1f, stiffness = 380f) else tween(MOTION_MEDIUM, easing = MotionEasing)

/**
 * The player sheet's settle: a spring rather than a fixed curve, so a release hands on the finger's
 * speed instead of restarting from rest. Not bouncy, so it comes to rest at open or closed rather than
 * overshooting either, and moderately stiff so it stays restrained. The threshold is a fraction of the
 * sheet's height, about a pixel on a phone, so the spring runs until it has arrived instead of ending
 * with a visible jump. Like every animation it follows the system animation scale, and finishes at
 * once when animations are off.
 */
internal val SheetSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 0.0005f)
