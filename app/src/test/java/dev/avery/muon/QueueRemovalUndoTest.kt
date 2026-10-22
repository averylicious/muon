package dev.avery.muon

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
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
    private lateinit var initial: List<MediaItem>

    @Before fun setUp() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        initial = listOf(1, 2, 2, 3).map(::item)
        player.setMediaItems(initial, 0, 0L)
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
        assertTrue(undo(removal))
        assertEquals(initial.map { it.mediaId }, ids(player))
        assertEquals(initial.map { it.mediaMetadata.title }, items(player).map { it.mediaMetadata.title })
        assertFalse(undo(removal))
    }

    @Test fun replacingWithIdenticalRemainingItemsInvalidatesOwnership() {
        val removal = remove(player, 1)
        val remaining = items(player)
        player.setMediaItems(remaining.map(::queueOccurrence), 0, 0L)
        assertEquals(remaining.map { it.mediaId }, ids(player))
        val replacement = items(player)
        assertFalse(undo(removal))
        assertEquals(replacement, items(player))
    }

    @Test fun replacementAndClearDoNotReceiveAnOldRemovedSong() {
        val removal = remove(player, 1)
        player.setMediaItems(listOf(c, a), 0, 0L)
        assertFalse(undo(removal))
        assertEquals(listOf(c, a), items(player))
        val second = remove(player, 1)
        player.clearMediaItems()
        assertFalse(undo(second))
        assertTrue(items(player).isEmpty())
    }

    @Test fun movingIdenticalDuplicatesStillInvalidatesUndo() {
        player.setMediaItems(listOf(1, 2, 2, 2).map(::item), 0, 0L)
        val removal = remove(player, 1)
        val before = items(player)
        player.moveMediaItem(1, 2)
        assertEquals(before.map { it.mediaId }, ids(player)) // Song-ID matching misses this reorder.
        val reordered = items(player)
        assertFalse(undo(removal))
        assertEquals(reordered, items(player))
    }

    @Test fun positionAndShuffleChangesKeepTheSurvivingPlaylistRestorable() {
        val removal = remove(player, 1)
        player.seekToDefaultPosition(1)
        player.shuffleModeEnabled = true
        assertTrue(undo(removal))
        assertEquals(initial.map { it.mediaId }, ids(player))
        assertEquals(initial.map { it.mediaMetadata.title }, items(player).map { it.mediaMetadata.title })
        assertTrue(player.shuffleModeEnabled)
    }

    @Test fun closedOrStaleRowsDoNotMutateTheQueue() {
        val removal = remove(player, 1)
        removal.close()
        removal.close()
        assertFalse(undo(removal))
        val before = items(player)
        assertNull(removeOrNull(player, -1, b))
        assertNull(removeOrNull(player, 50, b))
        assertNull(removeOrNull(player, 1, c))
        assertNull(removeOrNull(player, player.currentMediaItemIndex, a))
        assertEquals(before, items(player))
    }

    @Test fun connectedControllerAcknowledgementKeepsNormalUndoUsable() {
        val p = connectController()
        val removedItem = p.getMediaItemAt(1)
        val removal = remove(p, 1)
        barrier(p)
        assertEquals(3, player.mediaItemCount)
        assertTrue("A normal asynchronous session acknowledgement must not invalidate Undo", undo(removal))
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
        assertFalse(undo(removal))
        barrier(p)
        assertEquals(listOf(c.mediaId, a.mediaId), ids(player))
    }

    @Test fun missingOrClonedOccurrenceKeysCannotOfferUndo() {
        val raw = MediaItem.Builder().setMediaId(b.mediaId).setUri(restoreUrl(b.mediaId)).build()
        player.setMediaItems(listOf(a, raw, c), 0, 0L)
        val missing = remove(player, 1)
        assertFalse(missing.restorable)
        assertFalse(undo(missing))
        player.setMediaItems(listOf(a, b, b, b), 0, 0L)
        val ambiguous = remove(player, 1)
        assertFalse(ambiguous.restorable)
        assertFalse(undo(ambiguous))
    }

    @Test fun survivingDuplicateRowsKeepTheirKeysAndUndoCreatesANewRow() {
        val before = queueRowKeys(items(player))
        val removal = remove(player, 1)
        val after = queueRowKeys(items(player))
        assertEquals(before.filterIndexed { i, _ -> i != 1 }, after)
        assertTrue(undo(removal))
        val restored = queueRowKeys(items(player))
        assertNotEquals(before[1], restored[1])
        assertEquals(after, restored.filterIndexed { i, _ -> i != 1 })
        assertArrayEquals(initial[1].mediaMetadata.extras?.getByteArray(SONG_EXTRA),
            player.getMediaItemAt(1).mediaMetadata.extras?.getByteArray(SONG_EXTRA))
    }

    @Test fun duplicateReorderPreservesDistinctRowKeysAndLegacyFallbackHasNoCollisions() {
        val before = queueRowKeys(items(player))
        player.moveMediaItem(1, 2)
        assertEquals(before.moved(1, 2), queueRowKeys(items(player)))
        val raw = MediaItem.Builder().setMediaId(b.mediaId).setUri(restoreUrl(b.mediaId)).build()
        val legacy = queueRowKeys(listOf(raw, raw, raw))
        assertEquals(3, legacy.toSet().size)
        assertEquals(2, queueRowKeys(listOf(b, b)).toSet().size)
    }

    @Test fun shuffledUndoRestoresVisibleSlotEvenWhenRemovedItemWasLastInPlaylist() {
        player.setMediaItems(listOf(1, 2, 2, 3, 4, 5).map(::item), 0, 0L)
        player.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 2, 5, 4, 1, 3)))
        player.shuffleModeEnabled = true
        val before = queueShuffledOrder(player).map { player.getMediaItemAt(it).mediaId }
        val removed = player.getMediaItemAt(5)
        val removal = remove(player, 5) // Third in playing order; last in the underlying playlist.
        assertTrue(undo(removal))
        assertEquals(before, queueShuffledOrder(player).map { player.getMediaItemAt(it).mediaId })
        assertEquals(removed.mediaMetadata.title, player.getMediaItemAt(5).mediaMetadata.title)
        assertEquals(0, player.currentMediaItemIndex)
        assertTrue(player.shuffleModeEnabled)
    }

    @Test fun connectedControllerUndoRestoresShuffledDuplicatesThroughAcknowledgedSessionCommand() {
        player.setMediaItems(listOf(1, 2, 2, 3, 4, 2).map(::item), 0, 0L)
        player.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 2, 5, 4, 1, 3)))
        player.shuffleModeEnabled = true
        val p = connectController()
        val before = queueShuffledOrder(p).map { p.getMediaItemAt(it).mediaId }
        val survivor = queueOccurrenceKey(p.getMediaItemAt(2))
        val removal = remove(p, 5)
        barrier(p)
        assertTrue(undo(removal))
        barrier(p)
        assertEquals(before, queueShuffledOrder(player).map { player.getMediaItemAt(it).mediaId })
        assertEquals(survivor, queueOccurrenceKey(player.getMediaItemAt(2)))
        assertTrue(player.shuffleModeEnabled)
    }

    @Test fun savedUndoRechecksOwnershipAfterHeldAdmissionBeforeChangingPlayback() {
        val work = java.util.ArrayDeque<Runnable>()
        val executor = java.util.concurrent.Executor { work.add(it) }
        val ref = requireNotNull(SavedRef.download(SavedShelf.Phone, "saved/undo", "saved/undo"))
        val saved = queueOccurrence(MediaItem.Builder().setMediaId(ref.handle).setUri(ref.handle).build())
        player.setMediaItems(listOf(a, saved, c), 0, 0L)
        val p = connectController(PlaybackSessionCallback({ true }, executor))
        val removal = remove(p, 1)
        barrier(p)
        val future = removal.undo()
        pumpUntil { work.size == 1 }
        player.setMediaItems(listOf(c, a), 0, 0L)
        work.removeFirst().run()
        pumpUntil { future.isDone }
        assertFalse(future.get(1, TimeUnit.SECONDS))
        assertEquals(listOf(c.mediaId, a.mediaId), ids(player))
    }

    @Test fun reshuffleAfterRemovalIsNotOverwrittenByUndo() {
        player.setMediaItems(listOf(1, 2, 3, 4, 5).map(::item), 0, 0L)
        player.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 3, 1, 4, 2)))
        val removal = remove(player, 3)
        player.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 3, 2, 1)))
        assertTrue(undo(removal))
        // The deliberate new shuffle survives; inserting at playlist index3 follows its index2.
        assertEquals(listOf(0, 4, 2, 3, 1), queueShuffledOrder(player))
    }

    @Test fun pendingControllerEditCannotMisplaceTheShuffledUndoSlot() {
        player.setMediaItems(listOf(1, 2, 2, 3, 4, 2).map(::item), 0, 0L)
        player.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 2, 5, 4, 1, 3)))
        player.shuffleModeEnabled = true
        val p = connectController()
        // Add to queue, not yet acknowledged: pinned Media3 masks the controller's timeline unshuffled,
        // so an order captured there would put the restored row in the wrong slot.
        p.addMediaItem(queueOccurrence(item(9)))
        assertEquals((0..6).toList(), queueShuffledOrder(p))
        val removal = remove(p, 2)
        assertTrue(undo(removal))
        barrier(p)
        // The append went last in the shuffle; the restored duplicate is back second, as before removal.
        assertEquals(listOf(1, 2, 2, 4, 2, 3, 9).map { item(it).mediaId },
            queueShuffledOrder(player).map { player.getMediaItemAt(it).mediaId })
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test fun quickSecondRemovalOfAStaleRowIsRefusedAndTheFirstUndoRestoresItsExactDuplicateSlot() {
        player.setMediaItems(listOf(1, 2, 2, 3, 4, 2).map(::item), 0, 0L)
        player.setShuffleOrder(QueueShuffleOrder.restored(listOf(0, 2, 5, 4, 1, 3)))
        player.shuffleModeEnabled = true
        val p = connectController()
        val before = queueShuffledOrder(player).map { queueOccurrenceKey(player.getMediaItemAt(it)) }
        val stale = p.getMediaItemAt(4)
        val first = QueueRemovalUndo.remove(p, 2, p.getMediaItemAt(2))
        // Sent before the first is acknowledged, from the same unchanged view: index 4 now holds another row.
        val second = QueueRemovalUndo.remove(p, 4, stale)
        pumpUntil { first.isDone && second.isDone }
        val removal = requireNotNull(first.get()).also(removals::add)
        assertNull(second.get())
        assertEquals(5, player.mediaItemCount)
        assertTrue(queueOccurrenceKey(stale) in items(player).map(::queueOccurrenceKey))
        assertTrue(undo(removal))
        // Every surviving duplicate keeps its slot; the restored one is back at shuffled position 1.
        val after = queueShuffledOrder(player).map { queueOccurrenceKey(player.getMediaItemAt(it)) }
        assertEquals(before.filterIndexed { i, _ -> i != 1 }, after.filterIndexed { i, _ -> i != 1 })
        assertNotEquals(before[1], after[1])
        assertEquals(item(2).mediaId, player.getMediaItemAt(queueShuffledOrder(player)[1]).mediaId)
    }

    @Test fun undoTicketDoesNotCarryTheSurvivingSongsText() {
        val many = (1..1_500).map(::item)
        player.setMediaItems(many, 0, 0L)
        val survivingChars = many.filterIndexed { i, _ -> i != 700 }
            .sumOf { it.mediaId.length + requireNotNull(queueOccurrenceKey(it)).length }
        val ticket = requireNotNull(QueueRemovalRequest(700, many[700].mediaId, queueOccurrenceKey(many[700])).applyTo(player))
        assertNotNull(QueueRemovalTicket.fromBundle(ticket))
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(ticket)
            assertTrue("${parcel.dataSize()} bytes", parcel.dataSize() < survivingChars)
        } finally { parcel.recycle() }
    }

    private fun undo(removal: QueueRemovalUndo): Boolean {
        val future = removal.undo()
        pumpUntil { future.isDone }
        return future.get(1, TimeUnit.SECONDS)
    }

    /** Waits for the session's acknowledgement when [p] is a controller. */
    private fun removeOrNull(p: Player, index: Int, item: MediaItem): QueueRemovalUndo? {
        val future = QueueRemovalUndo.remove(p, index, item)
        pumpUntil { future.isDone }
        return future.get(1, TimeUnit.SECONDS)?.also(removals::add)
    }

    private fun remove(p: Player, index: Int) = requireNotNull(removeOrNull(p, index, p.getMediaItemAt(index)))

    private fun connectController(callback: PlaybackSessionCallback = PlaybackSessionCallback()): MediaController {
        val made = MediaSession.Builder(RuntimeEnvironment.getApplication(), player)
            .setCallback(callback).build().also { session = it }
        val future = MediaController.Builder(RuntimeEnvironment.getApplication(), made.token).buildAsync()
        pumpUntil { future.isDone }
        return future.get(1, TimeUnit.SECONDS).also { controller = it }
    }

    /** A real session command/result after prior edits; no sleeps stand in for acknowledgement. */
    private fun barrier(p: MediaController) {
        // Use an advertised command: an unknown action can be rejected in the controller without
        // reaching the service, and therefore cannot acknowledge earlier playlist operations.
        val result = p.sendCustomCommand(RESTORE_QUEUE_REMOVAL, Bundle.EMPTY)
        pumpUntil { result.isDone }
        assertEquals(androidx.media3.session.SessionError.ERROR_BAD_VALUE,
            result.get(1, TimeUnit.SECONDS).resultCode) // Empty Undo is rejected without mutation.
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
    private fun item(id: Int) = TauonTrack(id.toLong(), "Fixture $id", "Fixture artist", "Fixture album",
        3000L, true, false).mediaItem(ServerEndpoint.parse("http://192.168.1.20:7814"))
}
