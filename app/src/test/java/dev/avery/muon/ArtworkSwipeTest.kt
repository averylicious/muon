package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkSwipeTest {
    private val width = 800
    private val minimum = 48f

    @Test fun draggingLeftPastTheThresholdSelectsTheNextTrack() {
        assertEquals(SwipeAction.Next, swipeAction(-201f, width, minimum, hasNext = true, hasPrevious = true))
    }

    @Test fun draggingRightPastTheThresholdSelectsThePreviousTrack() {
        assertEquals(SwipeAction.Previous, swipeAction(201f, width, minimum, hasNext = true, hasPrevious = true))
    }

    @Test fun aShortDragDoesNothing() {
        listOf(-199f, -1f, 0f, 1f, 199f).forEach {
            assertEquals(SwipeAction.None, swipeAction(it, width, minimum, hasNext = true, hasPrevious = true))
        }
    }

    /** At the end of a queue the gesture must not act, however far it travels. */
    @Test fun aTrackThatIsNotThereIsNeverSelected() {
        assertEquals(SwipeAction.None, swipeAction(-600f, width, minimum, hasNext = false, hasPrevious = true))
        assertEquals(SwipeAction.None, swipeAction(600f, width, minimum, hasNext = true, hasPrevious = false))
    }

    /** A small or not yet measured artwork still needs a deliberate drag, not a twitch. */
    @Test fun theMinimumDistanceGovernsWhenTheArtworkIsSmall() {
        assertEquals(SwipeAction.None, swipeAction(-40f, width = 0, minimum = minimum, hasNext = true, hasPrevious = true))
        assertEquals(SwipeAction.Next, swipeAction(-49f, width = 0, minimum = minimum, hasNext = true, hasPrevious = true))
    }

    @Test fun artworkFollowsTheFingerTowardsATrackThatExists() {
        assertEquals(-120f, swipeOffset(-120f, hasNext = true, hasPrevious = true), 0.01f)
        assertEquals(120f, swipeOffset(120f, hasNext = true, hasPrevious = true), 0.01f)
    }

    @Test fun artworkResistsTowardsATrackThatDoesNot() {
        assertEquals(-40f, swipeOffset(-120f, hasNext = false, hasPrevious = true), 0.01f)
        assertEquals(40f, swipeOffset(120f, hasNext = true, hasPrevious = false), 0.01f)
    }

    private val playing = SwipeTarget(mediaId = "http://10.0.0.2:7814/12", index = 3, queueSize = 20)

    @Test fun aGestureActsWhenNothingMovedUnderIt() {
        assertTrue(swipeTargetUnchanged(playing, playing.copy()))
    }

    /** The track ended by itself mid-drag: releasing must not skip whatever replaced it. */
    @Test fun aTrackChangeDuringTheDragCancelsIt() {
        assertFalse(swipeTargetUnchanged(playing, playing.copy(mediaId = "http://10.0.0.2:7814/13", index = 4)))
    }

    /** The same track can appear twice, so the media ID alone cannot identify the gesture. */
    @Test fun theSameTrackAtADifferentQueuePositionCancelsIt() {
        assertFalse(swipeTargetUnchanged(playing, playing.copy(index = 9)))
    }

    /** A replaced queue is not the queue the gesture began in, even at the same position. */
    @Test fun aReplacedQueueCancelsIt() {
        assertFalse(swipeTargetUnchanged(playing, playing.copy(queueSize = 4)))
    }

    /** No controller when the drag began, or none when it ended: nothing to act on either way. */
    @Test fun anAbsentControllerCancelsIt() {
        assertFalse(swipeTargetUnchanged(null, playing))
        assertFalse(swipeTargetUnchanged(playing, null))
        assertFalse(swipeTargetUnchanged(null, null))
    }
}
