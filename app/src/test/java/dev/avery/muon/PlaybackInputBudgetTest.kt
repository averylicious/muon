package dev.avery.muon

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackInputBudgetTest {
    @Test fun exactUtf8BoundaryIncludesNulAndSupplementaryCharacters() {
        val item = MediaItem.Builder().setMediaMetadata(MediaMetadata.Builder().setTitle("A\u0000é😀").build()).build()
        assertTrue(playbackInputFits(listOf(item), SavedQueueLimits(textBytes = 8)))
        assertFalse(playbackInputFits(listOf(item), SavedQueueLimits(textBytes = 7)))
        assertTrue(playbackInputFits(listOf(item, item), SavedQueueLimits(textBytes = 16)))
        assertFalse(playbackInputFits(listOf(item, item), SavedQueueLimits(textBytes = 15)))
    }

    @Test fun recordAndArtworkBytesShareTheAggregateBudget() {
        val extras = Bundle().apply { putByteArray(SONG_EXTRA, ByteArray(5)) }
        val item = MediaItem.Builder().setMediaMetadata(MediaMetadata.Builder().setTitle("x")
            .setExtras(extras).setArtworkData(ByteArray(4), MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()
        assertTrue(playbackInputFits(listOf(item), SavedQueueLimits(textBytes = 10)))
        assertFalse(playbackInputFits(listOf(item), SavedQueueLimits(textBytes = 9)))
        assertFalse(playbackInputFits(listOf(item, item), SavedQueueLimits(textBytes = 19)))
    }

    @Test fun savedPreparationAtItsExactTextBoundaryAlsoPassesSessionAdmission() {
        val ref = requireNotNull(SavedRef.download(SavedShelf.Phone, "saved/example", "saved/example"))
        val entry = SavedEntry(ref, null, null, androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,
            SavedCoverage.Full, 1L, ownCover = false, removable = true)
        val bytes = ref.handle.toByteArray().size * 2L + entry.title().toByteArray().size +
            entry.subtitle().toByteArray().size + 36L
        val limits = SavedQueueLimits(textBytes = bytes)
        val exact = requireNotNull(prepareSavedQueue(listOf(entry), ref, limits))
        assertEquals(listOf(ref.handle), exact.items.map { it.mediaId })
        assertTrue(playbackInputFits(exact.items, limits))
        assertFalse(playbackInputFits(exact.items, limits.copy(textBytes = bytes - 1)))
    }

    @Test fun countRefusalDoesNotInspectOrCopyAnOversizedList() {
        val oversized = object : AbstractList<MediaItem>() {
            override val size: Int get() = 2_049
            override fun get(index: Int): MediaItem = throw AssertionError("Oversized input must not be read")
        }
        assertFalse(playbackInputFits(oversized))
        assertTrue(playbackInputFits(emptyList(), SavedQueueLimits(textBytes = 0)))
    }
}
