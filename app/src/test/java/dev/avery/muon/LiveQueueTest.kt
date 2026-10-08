package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LiveQueueTest {
    private val endpoint = ServerEndpoint.parse("http://192.168.1.2:7814")
    private fun song(id: Long, playable: Boolean = true, title: String = "Song $id") =
        TauonTrack(id, title, "A; B", "Album", 1234L, playable, false)
    private fun refused(action: () -> Unit) {
        try { action(); fail("Must refuse full oversized selection") } catch (_: PlaybackQueueLimit) { }
    }
    @Test fun preservesFullPlayableOrderDuplicatesSelectedFirstOccurrenceAndMetadata() {
        val tracks = listOf(song(1), song(9, false), song(2), song(2))
        val plan = requireNotNull(prepareLiveQueue(tracks, endpoint, 2))
        assertEquals(3, plan.items.size); assertEquals(1, plan.startIndex)
        assertEquals(listOf(1L, 2L, 2L), plan.items.map { decodeSong(it.mediaMetadata.extras!!.getByteArray(SONG_EXTRA)!!)!!.id })
        assertTrue(playbackInputFits(plan.items))
        assertEquals(3, plan.items.map(::queueOccurrenceKey).toSet().size)
        assertEquals("Song 2", plan.items[1].mediaMetadata.title)
    }
    @Test fun countRefusalPrecedesAnyMetadataEncodingAndNeverReturnsAPrefix() {
        val oversizedText = "x".repeat(100)
        refused { prepareLiveQueue(listOf(song(1, title = oversizedText), song(2), song(3)), endpoint,
            limits = SavedQueueLimits(2, 1000)) }
        assertNull(prepareLiveQueue(listOf(song(1, false)), endpoint))
        assertNull(prepareLiveQueue(listOf(song(1)), endpoint, 9))
    }
    @Test fun logicalMetadataIncludesEncodedRecordAndMultibyteOriginalText() {
        refused { prepareLiveQueue(listOf(song(1, title = "é😀\u0000".repeat(20))), endpoint,
            limits = SavedQueueLimits(textBytes = 80)) }
        val track = song(1, title = "é😀\u0000")
        val plan = requireNotNull(prepareLiveQueue(listOf(track), endpoint, 1))
        assertEquals(track, decodeSong(plan.items.single().mediaMetadata.extras!!.getByteArray(SONG_EXTRA)!!))
    }
}
