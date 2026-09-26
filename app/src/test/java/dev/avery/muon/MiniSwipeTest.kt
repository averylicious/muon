package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class MiniSwipeTest {
    private val width = 400
    private val minimum = 48f
    private val flick = 700f
    private fun act(drag: Float, velocity: Float = 0f, next: Boolean = true, previous: Boolean = true) =
        miniSwipeAction(drag, velocity, width, minimum, flick, next, previous)

    @Test fun farEnoughSkipsEitherWay() {
        assertEquals(SwipeAction.Next, act(-100f))
        assertEquals(SwipeAction.Previous, act(100f))
        assertEquals(SwipeAction.None, act(-99f))
    }

    @Test fun aQuickFlickSkipsShortOfTheDistance() {
        assertEquals(SwipeAction.Next, act(-30f, velocity = -900f))
        assertEquals(SwipeAction.Previous, act(30f, velocity = 900f))
        assertEquals(SwipeAction.None, act(-30f, velocity = -500f))
    }

    @Test fun aFlickBackAgainstTheDragDoesNothing() {
        assertEquals(SwipeAction.None, act(-30f, velocity = 900f))
        assertEquals(SwipeAction.None, act(30f, velocity = -900f))
    }

    @Test fun aMissingTrackIsNeverSelected() {
        assertEquals(SwipeAction.None, act(-200f, velocity = -900f, next = false))
        assertEquals(SwipeAction.None, act(200f, velocity = 900f, previous = false))
    }
}
