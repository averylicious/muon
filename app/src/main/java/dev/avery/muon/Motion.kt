package dev.avery.muon

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Muon's motion, defined once. Durations are deliberately short: the point is to show that a
 * surface changed, not to make the user wait for it.
 */
internal const val MOTION_SHORT = 150
internal const val MOTION_MEDIUM = 250

/** Material's standard easing: quick to leave, gentle to arrive. */
internal val MotionEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Material's easing for a back gesture's own progress, which moves most at the start so the
 * surface answers the finger immediately. Material 3 applies this to every predictive back.
 */
internal val PredictiveBackEasing: Easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

internal fun <T> motionShort(): FiniteAnimationSpec<T> = tween(MOTION_SHORT, easing = MotionEasing)
internal fun <T> motionMedium(): FiniteAnimationSpec<T> = tween(MOTION_MEDIUM, easing = MotionEasing)

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
