package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsHeaderTest {
    @Test fun aTallWindowAffordsTheLargeTitle() {
        assertEquals(152f, lyricsHeaderHeight(windowHeight = 800f, fontScale = 1f)!!, 0.01f)
    }

    /** Both the header and the space the words need grow with the text, so both are compared. */
    @Test fun aTallWindowStillAffordsItAtLargeTextSizes() {
        assertEquals(304f, lyricsHeaderHeight(windowHeight = 800f, fontScale = 2f)!!, 0.01f)
    }

    /** Landscape and split screen: a large title would leave a few lines of song. */
    @Test fun aShortWindowFallsBackToTheCompactHeader() {
        assertNull(lyricsHeaderHeight(windowHeight = 300f, fontScale = 1f))
        assertNull(lyricsHeaderHeight(windowHeight = 360f, fontScale = 2f))
    }

    @Test fun theDecisionTurnsOnLeavingEnoughRoomForTheWords() {
        assertEquals(152f, lyricsHeaderHeight(windowHeight = 352f, fontScale = 1f)!!, 0.01f)
        assertNull(lyricsHeaderHeight(windowHeight = 351f, fontScale = 1f))
    }

    /** A font scale beyond the clamp must not make the header grow without bound. */
    @Test fun anExtremeFontScaleIsClamped() {
        assertEquals(304f, lyricsHeaderHeight(windowHeight = 2000f, fontScale = 5f)!!, 0.01f)
    }
}
