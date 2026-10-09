package dev.avery.muon

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual playlists without prepare/audio/network; Binder and snackbar QA remain manual. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class QueueInsertionTest {
    private lateinit var player: ExoPlayer
    private val a = song("a")
    private val b = song("b")
    @Before fun setUp() { player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build() }
    @After fun tearDown() { player.release() }

    @Test fun ordinaryUndoPreservesSongAndExistingExtras() {
        val original = a.buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle("A")
            .setExtras(Bundle().apply { putByteArray(SONG_EXTRA, byteArrayOf(1, 2, 3)) }).build()).build()
        val insertion = queueInsertion(original)
        assertEquals(original.mediaId, insertion.item.mediaId)
        assertEquals(original.localConfiguration, insertion.item.localConfiguration)
        assertEquals("A", insertion.item.mediaMetadata.title)
        assertArrayEquals(byteArrayOf(1, 2, 3), insertion.item.mediaMetadata.extras!!.getByteArray(SONG_EXTRA))
        assertEquals(1, original.mediaMetadata.extras!!.size())
        player.setMediaItems(listOf(a, b)); player.addMediaItem(1, insertion.item)
        assertTrue(undoQueueInsertion(player, insertion))
        assertEquals(listOf("a", "b"), ids())
    }
    @Test fun movedInsertionIsRemovedInsteadOfNearestDuplicate() {
        val insertion = queueInsertion(a)
        player.setMediaItems(listOf(b, a, b, b)); player.addMediaItem(1, insertion.item)
        player.moveMediaItem(1, 4)
        assertEquals("a", player.getMediaItemAt(1).mediaId)
        assertTrue(undoQueueInsertion(player, insertion))
        assertEquals(listOf("b", "a", "b", "b"), ids())
    }
    @Test fun removedInsertionDoesNotRemoveAnotherDuplicate() {
        val insertion = queueInsertion(a)
        player.setMediaItems(listOf(b, a)); player.addMediaItem(1, insertion.item)
        player.removeMediaItem(1)
        assertFalse(undoQueueInsertion(player, insertion))
        assertEquals(listOf("b", "a"), ids())
    }
    @Test fun replacementQueueWithSameSongsIsNotChanged() {
        val insertion = queueInsertion(a)
        player.setMediaItems(listOf(b, a)); player.addMediaItem(1, insertion.item)
        player.setMediaItems(listOf(a, b, a))
        assertFalse(undoQueueInsertion(player, insertion))
        assertEquals(listOf("a", "b", "a"), ids())
    }
    @Test fun metadataBundleRoundTripRetainsInsertionIdentity() {
        val insertion = queueInsertion(a)
        val restored = MediaItem.fromBundle(insertion.item.toBundle()).buildUpon()
            .setUri(insertion.item.localConfiguration!!.uri).build()
        player.setMediaItems(listOf(a, restored))
        assertTrue(undoQueueInsertion(player, insertion))
        assertEquals(listOf("a"), ids())
    }
    @Test fun repeatedTokenCannotDeleteEitherEntry() {
        val insertion = queueInsertion(a)
        player.setMediaItems(listOf(insertion.item, insertion.item))
        assertFalse(undoQueueInsertion(player, insertion))
        assertEquals(2, player.mediaItemCount)
        val other = queueInsertion(a)
        player.setMediaItems(listOf(insertion.item, other.item))
        assertTrue(undoQueueInsertion(player, insertion))
        assertTrue(undoQueueInsertion(player, other))
        assertEquals(0, player.mediaItemCount)
    }
    private fun ids() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
    private fun song(id: String) = MediaItem.Builder().setMediaId(id)
        .setUri("http://192.168.1.10:7814/api1/file/42").build()
}
