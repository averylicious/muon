package dev.avery.muon

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the combined production helpers/factory; no prepare, network or Compose/UI claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueIntegrationTest {
    private lateinit var player: ExoPlayer
    private val endpoint = ServerEndpoint.parse("192.168.1.10")
    @Before fun setUp() { player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build() }
    @After fun tearDown() { player.release() }

    @Test fun insertionCopiesOccurrenceAndSongRecordThroughMetadataSerialization() {
        val track = song(2)
        val item = track.mediaItem(endpoint)
        val insertion = queueInsertion(item)
        // Controller serialization excludes the local playback configuration. Restore the validated
        // URI for this ExoPlayer fixture, as the production session does; retain round-tripped extras.
        val restored = MediaItem.fromBundle(insertion.item.toBundle()).buildUpon()
            .setUri(insertion.item.localConfiguration!!.uri).build()
        assertNotNull(queueOccurrenceKey(item))
        assertEquals(queueOccurrenceKey(item), queueOccurrenceKey(restored))
        assertArrayEquals(encodeSong(track), restored.mediaMetadata.extras!!.getByteArray(SONG_EXTRA))
        player.setMediaItems(listOf(song(1).mediaItem(endpoint), restored))
        assertTrue(undoQueueInsertion(player, insertion))
        assertEquals(1, player.mediaItemCount)
    }

    @Test fun movedInsertedDuplicateUndoPreservesEveryOtherOccurrence() {
        val originals = listOf(1, 2, 2, 3).map { song(it).mediaItem(endpoint) }
        player.setMediaItems(originals)
        val insertion = queueInsertion(song(2).mediaItem(endpoint))
        player.addMediaItem(1, insertion.item)
        assertEquals(5, queueRowKeys(items()).toSet().size)
        player.moveMediaItem(1, 4)
        assertTrue(undoQueueInsertion(player, insertion))
        assertEquals(originals.map(::queueOccurrenceKey), items().map(::queueOccurrenceKey))
        assertEquals(queueRowKeys(originals), queueRowKeys(items()))
    }

    @Test fun rowGuardAndRemovalUndoKeepSurvivingDuplicateKeys() {
        val originals = listOf(1, 2, 2, 3).map { song(it).mediaItem(endpoint) }
        player.setMediaItems(originals)
        val stamp = QueueActionStamp(7, player.currentTimeline, player.currentMediaItemIndex, false)
        assertTrue(queueEntryCurrent(player, stamp, 7, 1, originals[1]))
        val removal = requireNotNull(QueueRemovalUndo.remove(player, 1, originals[1]))
        try {
            assertFalse(queueEntryCurrent(player, stamp, 7, 1, originals[2]))
            val kept = listOf(originals[0], originals[2], originals[3])
            assertEquals(queueRowKeys(kept), queueRowKeys(items()))
            assertTrue(removal.undo())
            assertEquals(originals.map { it.mediaId }, items().map { it.mediaId })
            assertNotEquals(queueOccurrenceKey(originals[1]), queueOccurrenceKey(player.getMediaItemAt(1)))
            assertEquals(queueOccurrenceKey(originals[2]), queueOccurrenceKey(player.getMediaItemAt(2)))
            assertArrayEquals(encodeSong(song(2)), player.getMediaItemAt(1).mediaMetadata.extras!!.getByteArray(SONG_EXTRA))
        } finally { removal.close() }
    }

    @Test fun aReadOnlyPlayerCannotClaimInsertionUndoSucceeded() {
        val insertion = queueInsertion(song(2).mediaItem(endpoint))
        player.setMediaItems(listOf(song(1).mediaItem(endpoint), insertion.item))
        val readOnly = object : ForwardingPlayer(player) {
            override fun isCommandAvailable(command: Int): Boolean =
                command != Player.COMMAND_CHANGE_MEDIA_ITEMS && super.isCommandAvailable(command)
        }
        val before = items()
        assertFalse(undoQueueInsertion(readOnly, insertion))
        assertEquals(before, items())
    }

    private fun items() = (0 until player.mediaItemCount).map(player::getMediaItemAt)
    private fun song(id: Int) = TauonTrack(id.toLong(), "Song$id", "Artist", "Album", 120_000, true, false)
}
