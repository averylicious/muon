package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LoudnessNoticeTest {
    @Test fun onlyExaminedSelectedAudioCanBeCalledMissing() {
        assertEquals(LoudnessSource.Checking, loudnessPlayback(null, true, false, null, true).source)
        assertEquals(LoudnessSource.Checking, loudnessPlayback("song", false, false, null, true).source)
        val missing = loudnessPlayback("song", true, false, null, true)
        assertTrue(missing.missingTags)
        assertFalse(missing.usable)
        val estimated = loudnessPlayback("song", true, false, -12f, true)
        assertTrue(estimated.missingTags)
        assertTrue(estimated.usable)
        val tagged = loudnessPlayback("song", true, true, 0f, true)
        assertFalse(tagged.missingTags)
        assertTrue(tagged.usable) // A valid 0 dB tag still means measured loudness data exists.
    }

    @Test fun bufferingAndPausedSongsDoNotConsumeTheReminderAndRepeatedCurrentSongsDoNotSpam() {
        val history = LoudnessNoticeHistory()
        assertFalse(history.shouldShow(loudnessPlayback("song", false, false, null, true)))
        assertFalse(history.shouldShow(loudnessPlayback("song", true, false, null, false)))
        assertTrue(history.shouldShow(loudnessPlayback("song", true, false, null, true)))
        assertFalse(history.shouldShow(loudnessPlayback("song", true, false, -10f, true)))
        assertFalse(history.shouldShow(loudnessPlayback("tagged", true, true, -8f, true)))
        assertTrue(history.shouldShow(loudnessPlayback("other", true, false, -10f, true)))
    }

    @Test fun reminderRetentionIsBoundedAndRecentEntriesRemainSuppressed() {
        val history = LoudnessNoticeHistory(2)
        fun playing(id: String) = loudnessPlayback(id, true, false, null, true)
        assertTrue(history.shouldShow(playing("a")))
        assertTrue(history.shouldShow(playing("b")))
        assertTrue(history.shouldShow(playing("c")))
        assertFalse(history.shouldShow(playing("b")))
        assertFalse(history.shouldShow(playing("c")))
        assertTrue(history.shouldShow(playing("a"))) // Outside the bounded recent window, it may remind again.
    }
}
