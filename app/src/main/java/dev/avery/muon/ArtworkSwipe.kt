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

/**
 * What the player was showing when a drag began, and what it would have done on release.
 *
 * Repeated tracks share a media ID, so the index is part of the identity, and a queue can be
 * replaced by one of the same length, so [revision] carries the player's own event history. The
 * neighbour indices are the player's answers rather than index arithmetic, so shuffle and repeat
 * are already accounted for; the command flags say whether it would accept the move at all.
 */
internal data class SwipeTarget(
    val revision: Int,
    val mediaId: String?,
    val index: Int,
    val queueSize: Int,
    val nextIndex: Int,
    val previousIndex: Int,
    val canNext: Boolean,
    val canPrevious: Boolean,
)

/**
 * Whether a gesture may still act.
 *
 * A drag belongs to the track it started on, and to the neighbours it was showing. If that track
 * ends by itself mid-drag, releasing must not skip the track that replaced it, so anything that
 * moved — the track, its position, the queue, its order, or what the player will accept — cancels
 * the gesture. Being conservative costs a spring-back; being permissive skips a track the user
 * never asked to skip, or commits to a cover they were never shown.
 */
internal fun swipeTargetUnchanged(start: SwipeTarget?, now: SwipeTarget?): Boolean =
    start != null && start == now

/**
 * How far the artwork is allowed to be drawn from home: one artwork width, so a long drag cannot
 * push every cover out of the viewport. The finger's raw distance still decides what a release
 * does, so clamping the picture never changes the outcome.
 */
internal fun clampedSwipeOffset(offset: Float, width: Int): Float =
    offset.coerceIn(-width.toFloat(), width.toFloat())
