package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
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
    @Test fun reusedIdWithNewQueuedLabelsGetsItsOwnReminderButDuplicateOccurrencesDoNotSpam() {
        fun state(title: String, artist: String = "Artist", album: String = "Album", playing: Boolean = true) =
            loudnessPlayback("same-origin-and-id", true, false, -12f, playing,
                loudnessNoticeKey(MediaItem.Builder().setMediaId("same-origin-and-id")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).build()).build()))
        val history = LoudnessNoticeHistory(2)
        val first = state("First")
        val second = state("Second")
        assertNotEquals("Compose's state/effect key must observe the reused-ID change", first, second)
        assertTrue(history.shouldShow(first))
        assertFalse(history.shouldShow(state("First"))) // Newly constructed duplicate queue item.
        assertFalse(history.shouldShow(state("Second", playing = false)))
        assertTrue(history.shouldShow(second))
        assertFalse(history.shouldShow(state("Second")))
        assertTrue(history.shouldShow(state("Second", artist = "Other artist")))
        assertTrue(history.shouldShow(first)) // Evicted by the existing bounded two-entry policy.
    }

}
