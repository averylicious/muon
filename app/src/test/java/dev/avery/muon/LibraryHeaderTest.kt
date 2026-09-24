package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryHeaderTest {
    @Test fun aTallWindowAffordsTheGreeting() {
        assertEquals(152f, libraryHeaderHeight(windowHeight = 900f, fontScale = 1f)!!, 0.01f)
    }

    /** Both the greeting and the list it sits above grow with the text, so both are compared. */
    @Test fun aTallWindowStillAffordsItAtLargeTextSizes() {
        assertEquals(304f, libraryHeaderHeight(windowHeight = 900f, fontScale = 2f)!!, 0.01f)
    }

    /** Landscape and split screen: a greeting would leave a couple of rows of music. */
    @Test fun aShortWindowFallsBackToTheCompactBar() {
        assertNull(libraryHeaderHeight(windowHeight = 360f, fontScale = 1f))
        assertNull(libraryHeaderHeight(windowHeight = 700f, fontScale = 2f))
    }

    @Test fun theDecisionTurnsOnLeavingEnoughRoomForTheList() {
        assertEquals(152f, libraryHeaderHeight(windowHeight = 392f, fontScale = 1f)!!, 0.01f)
        assertNull(libraryHeaderHeight(windowHeight = 391f, fontScale = 1f))
    }

    /** At accessibility text sizes an ordinary phone window is no longer tall enough. */
    @Test fun anExtremeTextSizeFallsBackEvenInATallWindow() {
        assertNull(libraryHeaderHeight(windowHeight = 900f, fontScale = 3f))
    }

    @Test fun aSmallTextSizeDoesNotShrinkTheGreeting() {
        assertEquals(152f, libraryHeaderHeight(windowHeight = 900f, fontScale = 0.85f)!!, 0.01f)
    }
}
