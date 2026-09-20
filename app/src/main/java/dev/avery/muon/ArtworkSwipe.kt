package dev.avery.muon

/** What a finished horizontal drag across the artwork should do. */
internal enum class SwipeAction { None, Next, Previous }

/** A quarter of the artwork's width commits the swipe, so the distance scales with the image. */
internal const val SWIPE_FRACTION = 0.25f

/**
 * Swipe left for the next track, right for the previous one.
 *
 * The decision is made on how far the finger travelled, not on how far the artwork moved, so the
 * resistance applied by [swipeOffset] cannot change what a drag means. A track that is not there
 * is never selected: at the end of a queue, a leftward drag is simply a drag.
 */
internal fun swipeAction(drag: Float, width: Int, minimum: Float,
    hasNext: Boolean, hasPrevious: Boolean): SwipeAction {
    val threshold = maxOf(width * SWIPE_FRACTION, minimum)
    return when {
        drag <= -threshold && hasNext -> SwipeAction.Next
        drag >= threshold && hasPrevious -> SwipeAction.Previous
        else -> SwipeAction.None
    }
}

/**
 * How far the artwork follows the finger. It tracks one to one towards a track that exists, and
 * drags heavily towards one that does not, which says "nothing that way" without freezing.
 */
internal fun swipeOffset(drag: Float, hasNext: Boolean, hasPrevious: Boolean): Float =
    if ((drag < 0f && !hasNext) || (drag > 0f && !hasPrevious)) drag / 3f else drag
