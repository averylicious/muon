package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
    @Test fun storedWordsAreShownExactlyAsTauonReturnedThem() {
        val words = "First line\n\nSecond line   \n"
        assertEquals(words, storedLyrics(words))
    }

    /** Tauon returns an empty body rather than an error for a track it has no lyrics for. */
    @Test fun anEmptyAnswerBecomesAnExplanation() {
        listOf("", "   ", "\n\t\n").forEach {
            assertEquals("No lyrics stored for this track in Tauon.", storedLyrics(it))
        }
    }
}
