package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackQueuePlayerTest {
    private lateinit var native: ExoPlayer
    private lateinit var player: PlaybackQueuePlayer
    private var refusals = 0
    @Before fun setup() {
        native = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        player = PlaybackQueuePlayer(native, SavedQueueLimits(3, 64)) { refusals++ }
    }
    @After fun cleanup() { native.release() }
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("http://h/$id").build()
    private fun ids() = (0 until native.mediaItemCount).map { native.getMediaItemAt(it).mediaId }
    private fun refused(action: () -> Unit) {
        try { action(); fail("Oversized edit must fail") } catch (_: PlaybackQueueLimit) { }
    }
    @Test fun repeatedAddCannotGrowTheFinalQueueAndRemovalFreesCapacity() {
        player.setMediaItem(item("a")); player.addMediaItem(item("b")); player.addMediaItems(listOf(item("c")))
        native.shuffleModeEnabled = true; native.repeatMode = Player.REPEAT_MODE_ALL
        refused { player.addMediaItem(item("d")) }
        refused { player.addMediaItem(0, item("d")) }
        refused { player.addMediaItems(listOf(item("d"))) }
        refused { player.addMediaItems(0, listOf(item("d"))) }
        assertEquals(listOf("a", "b", "c"), ids()); assertEquals(4, refusals)
        assertTrue(native.shuffleModeEnabled); assertEquals(Player.REPEAT_MODE_ALL, native.repeatMode)
        player.removeMediaItem(1); player.addMediaItem(1, item("d"))
        assertEquals(listOf("a", "d", "c"), ids())
    }
    @Test fun everySetOverloadChecksBeforeChangingQueuePositionOrModes() {
        player.setMediaItems(listOf(item("a"), item("b")), 1, 1234L)
        native.shuffleModeEnabled = true; native.repeatMode = Player.REPEAT_MODE_ONE
        val tooMany = List(4) { item("$it") }
        refused { player.setMediaItems(tooMany) }
        refused { player.setMediaItems(tooMany, false) }
        refused { player.setMediaItems(tooMany, 0, 0L) }
        val giant = item("z").buildUpon().setMediaMetadata(MediaMetadata.Builder().setTitle("x".repeat(80)).build()).build()
        refused { player.setMediaItem(giant) }; refused { player.setMediaItem(giant, 0L) }
        refused { player.setMediaItem(giant, false) }
        assertEquals(listOf("a", "b"), ids()); assertEquals(1, native.currentMediaItemIndex)
        assertEquals(1234L, native.currentPosition); assertTrue(native.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ONE, native.repeatMode)
    }
    @Test fun replacementAccountsForRemovedItemsRatherThanJustAddingInputCost() {
        player.setMediaItems(listOf(item("a"), item("b"), item("c")))
        player.replaceMediaItem(1, item("d")); assertEquals(listOf("a", "d", "c"), ids())
        refused { player.replaceMediaItems(0, 1, listOf(item("e"), item("f"))) }
        assertEquals(listOf("a", "d", "c"), ids())
        player.replaceMediaItems(0, 2, listOf(item("e"), item("e")))
        assertEquals(listOf("e", "e", "c"), ids()) // Duplicates remain meaningful occurrences.
        player.moveMediaItem(2, 0); assertEquals(listOf("c", "e", "e"), ids())
        player.clearMediaItems(); assertTrue(ids().isEmpty())
    }
    @Test fun aggregateMetadataCanRefuseAnEditWithCountSpaceRemaining() {
        player = PlaybackQueuePlayer(native, SavedQueueLimits(3, 18)) { refusals++ }
        player.setMediaItem(item("a")) // mediaId + URI = 11 bytes.
        refused { player.addMediaItem(item("b")) }
        assertEquals(listOf("a"), ids())
        player.replaceMediaItem(0, item("b")); assertEquals(listOf("b"), ids())
    }
    @Test fun hugeIncomingCountIsRejectedWithoutReadingOrCopyingItsItems() {
        player.setMediaItem(item("a"))
        val huge = object : AbstractList<MediaItem>() {
            override val size get() = Int.MAX_VALUE
            override fun get(index: Int): MediaItem = throw AssertionError("Must not inspect huge input")
        }
        refused { player.addMediaItems(huge) }; refused { player.setMediaItems(huge) }
        assertEquals(listOf("a"), ids())
    }
    @Test fun realSessionReturnsFailureForFinalRefusalWithoutClearingNativeQueue() {
        val context = RuntimeEnvironment.getApplication()
        val endpoint = ServerEndpoint.parse("http://192.168.1.2:7814")
        fun song(id: Long) = TauonTrack(id, "S", "A", "B", 1000L, true, false).mediaItem(endpoint)
        player = PlaybackQueuePlayer(native, SavedQueueLimits(2, 10_000)) { refusals++ }
        player.setMediaItems(listOf(song(1), song(2)))
        val session = androidx.media3.session.MediaSession.Builder(context, player)
            .setCallback(PlaybackSessionCallback()).build()
        var controller: androidx.media3.session.MediaController? = null
        try {
            val connection = androidx.media3.session.MediaController.Builder(context, session.token).buildAsync()
            pump { connection.isDone }
            val connected = connection.get(1, java.util.concurrent.TimeUnit.SECONDS).also { controller = it }
            connected.addMediaItem(song(3))
            val barrier = connected.sendCustomCommand(
                androidx.media3.session.SessionCommand("dev.avery.muon.test.barrier", android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
            pump { barrier.isDone }; barrier.get(1, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(2, native.mediaItemCount); assertEquals(1, refusals)
            // The optimistic controller view must reconcile with the unchanged actual player.
            assertEquals(2, connected.mediaItemCount)
        } finally { controller?.release(); session.release() }
    }

    @Test fun acknowledgedInsertionUsesActualQueueAndPreservesNextAndAppendInShuffle() {
        val context = RuntimeEnvironment.getApplication()
        val endpoint = ServerEndpoint.parse("http://192.168.1.2:7814")
        fun song(id: Long) = TauonTrack(id, "S", "A", "B", 1000L, true, false).mediaItem(endpoint)
        player = PlaybackQueuePlayer(native, SavedQueueLimits(5, 10_000)) { refusals++ }
        player.setMediaItems(listOf(song(1), song(2), song(3)), 2, 0L)
        native.shuffleModeEnabled = true
        native.setShuffleOrder(QueueShuffleOrder.restored(listOf(2, 0, 1)))
        val session = MediaSession.Builder(context, player)
            .setCallback(PlaybackSessionCallback(shufflePlayer = native)).build()
        var controller: MediaController? = null
        try {
            val connection = MediaController.Builder(context, session.token).buildAsync()
            pump { connection.isDone }
            val connected = connection.get(1, TimeUnit.SECONDS).also { controller = it }
            fun capture(id: Long, next: Boolean) =
                requireNotNull(QueueInsertionRequest.capture(connected, queueInsertion(song(id)), next))
            fun confirm(request: QueueInsertionRequest): Boolean {
                val receipt = CoroutineScope(Dispatchers.Main.immediate).async { request.confirm(connected) }
                pump { receipt.isCompleted }
                return runBlocking { receipt.await() }
            }
            val next = capture(2, next = true)
            assertTrue(confirm(next))
            assertEquals(listOf(2, 3, 0, 1), queueShuffledOrder(native))
            assertEquals(3, native.nextMediaItemIndex)
            assertEquals(native.getMediaItemAt(1).mediaId, native.getMediaItemAt(3).mediaId)
            assertNotEquals(queueOccurrenceKey(native.getMediaItemAt(1)), queueOccurrenceKey(native.getMediaItemAt(3)))
            assertFalse(confirm(next)) // The same insertion cannot be applied twice.

            val append = capture(4, next = false)
            val staleNext = capture(5, next = true)
            native.seekToDefaultPosition(0)
            assertFalse(confirm(staleNext))
            assertTrue(confirm(append)) // The same queue can still accept an append after a track transition.
            assertEquals(listOf(2, 3, 0, 1, 4), queueShuffledOrder(native))
            val full = capture(5, next = false)
            assertFalse(confirm(full))
            assertEquals(5, native.mediaItemCount)
            assertEquals(1, refusals)

            player.setMediaItems(listOf(song(1), song(2), song(3)))
            assertFalse(confirm(full)) // A new queue of the same songs has different occurrences.
            assertEquals(3, native.mediaItemCount)
        } finally { controller?.release(); session.release() }
    }

    private fun pump(done: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(1)
        } while (System.nanoTime() < until)
        fail("Real session did not settle")
    }
}
