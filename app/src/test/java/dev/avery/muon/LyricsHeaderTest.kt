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

    /**
     * At accessibility text sizes the words are drawn that big, so the header is measured that big
     * too: an ordinary tall window no longer affords one.
     */
    @Test fun anExtremeTextSizeFallsBackEvenInATallWindow() {
        assertNull(lyricsHeaderHeight(windowHeight = 800f, fontScale = 3f))
        assertEquals(456f, lyricsHeaderHeight(windowHeight = 1400f, fontScale = 3f)!!, 0.01f)
    }

    /** A scale below one is treated as one; nothing shrinks the header below its design size. */
    @Test fun aSmallTextSizeDoesNotShrinkTheHeader() {
        assertEquals(152f, lyricsHeaderHeight(windowHeight = 800f, fontScale = 0.85f)!!, 0.01f)
    }
}
