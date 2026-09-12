package dev.avery.muon

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * Muon's motion, defined once. Durations are deliberately short: the point is to show that a
 * surface changed, not to make the user wait for it.
 */
internal const val MOTION_SHORT = 150
internal const val MOTION_MEDIUM = 250

/** Material's standard easing: quick to leave, gentle to arrive. */
internal val MotionEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

internal fun <T> motionShort(): FiniteAnimationSpec<T> = tween(MOTION_SHORT, easing = MotionEasing)
internal fun <T> motionMedium(): FiniteAnimationSpec<T> = tween(MOTION_MEDIUM, easing = MotionEasing)
