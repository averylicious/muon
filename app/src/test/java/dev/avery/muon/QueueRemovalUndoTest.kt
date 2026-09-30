package dev.avery.muon

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

/** Actual production helper and Media3 playlist/controller operations; no prepare/audio/network/UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueueRemovalUndoTest {
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var controller: MediaController? = null
    private val removals = ArrayList<QueueRemovalUndo>()
    private val a = item(1)
    private val b = item(2)
    private val c = item(3)

    @Before fun setUp() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        player.setMediaItems(listOf(a, b, b, c), 0, 0L)
    }
    @After fun tearDown() {
        removals.forEach(QueueRemovalUndo::close)
        controller?.release()
        session?.release()
        player.release()
    }

    @Test fun unchangedQueueRestoresExactDuplicateOccurrenceAndMetadataOnce() {
        val removal = remove(player, 1)
        assertEquals(listOf(a.mediaId, b.mediaId, c.mediaId), ids(player))
        assertTrue(removal.restorable)
        assertTrue(removal.undo())
        assertEquals(listOf(a, b, b, c), items(player))
        assertFalse(removal.undo())
    }

    @Test fun replacingWithIdenticalRemainingItemsInvalidatesOwnership() {
        val removal = remove(player, 1)
        val remaining = items(player)
        player.setMediaItems(remaining, 0, 0L)
        assertEquals(remaining, items(player))
        assertFalse(removal.undo())
        assertEquals(remaining, items(player))
    }

    @Test fun replacementAndClearDoNotReceiveAnOldRemovedSong() {
        val removal = remove(player, 1)
        player.setMediaItems(listOf(c, a), 0, 0L)
        assertFalse(removal.undo())
        assertEquals(listOf(c, a), items(player))
        val second = remove(player, 1)
        player.clearMediaItems()
        assertFalse(second.undo())
        assertTrue(items(player).isEmpty())
    }

    @Test fun movingIdenticalDuplicatesStillInvalidatesUndo() {
        player.setMediaItems(listOf(a, b, b, b), 0, 0L)
        val removal = remove(player, 1)
        val before = items(player)
        player.moveMediaItem(1, 2)
        assertEquals(before, items(player)) // ID/structural-list matching alone cannot detect this.
        assertFalse(removal.undo())
        assertEquals(before, items(player))
    }

    @Test fun positionAndShuffleChangesKeepTheSurvivingPlaylistRestorable() {
        val removal = remove(player, 1)
        player.seekToDefaultPosition(1)
        player.shuffleModeEnabled = true
        assertTrue(removal.undo())
        assertEquals(listOf(a, b, b, c), items(player))
        assertTrue(player.shuffleModeEnabled)
    }

    @Test fun closedOrStaleRowsDoNotMutateTheQueue() {
        val removal = remove(player, 1)
        removal.close()
        removal.close()
        assertFalse(removal.undo())
        val before = items(player)
        assertNull(QueueRemovalUndo.remove(player, -1, b))
        assertNull(QueueRemovalUndo.remove(player, 50, b))
        assertNull(QueueRemovalUndo.remove(player, 1, c))
        assertNull(QueueRemovalUndo.remove(player, player.currentMediaItemIndex, a))
        assertEquals(before, items(player))
    }

    @Test fun connectedControllerAcknowledgementKeepsNormalUndoUsable() {
        val p = connectController()
        val removedItem = p.getMediaItemAt(1)
        val removal = remove(p, 1)
        barrier(p)
        assertEquals(3, player.mediaItemCount)
        assertTrue("A normal asynchronous session acknowledgement must not invalidate Undo", removal.undo())
        barrier(p)
        assertEquals(4, player.mediaItemCount)
        assertEquals(removedItem.mediaId, player.getMediaItemAt(1).mediaId)
        assertEquals(removedItem.mediaMetadata.title, player.getMediaItemAt(1).mediaMetadata.title)
        assertEquals(restoreUrl(removedItem.mediaId), player.getMediaItemAt(1).localConfiguration?.uri.toString())
    }

    @Test fun connectedControllerRejectsAReplacementReceivedFromItsSession() {
        val p = connectController()
        val removal = remove(p, 1)
        barrier(p)
        player.setMediaItems(listOf(c, a), 0, 0L)
        pumpUntil { ids(p) == listOf(c.mediaId, a.mediaId) }
        assertFalse(removal.undo())
        barrier(p)
        assertEquals(listOf(c.mediaId, a.mediaId), ids(player))
    }

    private fun remove(p: Player, index: Int) =
        requireNotNull(QueueRemovalUndo.remove(p, index, p.getMediaItemAt(index))).also(removals::add)

    private fun connectController(): MediaController {
        val made = MediaSession.Builder(RuntimeEnvironment.getApplication(), player)
            .setCallback(PlaybackSessionCallback()).build().also { session = it }
        val future = MediaController.Builder(RuntimeEnvironment.getApplication(), made.token).buildAsync()
        pumpUntil { future.isDone }
        return future.get(1, TimeUnit.SECONDS).also { controller = it }
    }

    /** A real session command/result after prior edits; no sleeps stand in for acknowledgement. */
    private fun barrier(p: MediaController) {
        val result = p.sendCustomCommand(SessionCommand("dev.avery.muon.test.barrier", Bundle.EMPTY), Bundle.EMPTY)
        pumpUntil { result.isDone }
        result.get(1, TimeUnit.SECONDS) // Default callback rejects the custom command after processing it.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun pumpUntil(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(1)
        } while (System.nanoTime() < until)
        fail("Timed out waiting for the actual Media3 controller/session callback")
    }
    private fun ids(p: Player) = items(p).map { it.mediaId }
    private fun items(p: Player) = List(p.mediaItemCount, p::getMediaItemAt)
    private fun item(id: Int) = MediaItem.Builder().setMediaId("http://192.168.1.20:7814/$id")
        .setUri("http://192.168.1.20:7814/api1/file/$id")
        .setMediaMetadata(MediaMetadata.Builder().setTitle("Fixture $id").setArtist("Fixture artist").build()).build()
}
