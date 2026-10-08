package dev.avery.muon

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Media3 playlist mutations; no prepare, audio, network or Compose gesture testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class QueueActionTest {
    private lateinit var player: ExoPlayer
    private val a = item("a")
    private val b = item("b")
    private var revision = 0
    @Before fun setUp() {
        player = ExoPlayer.Builder(RuntimeEnvironment.getApplication()).build()
        player.setMediaItems(listOf(a, a, b), 0, 0L)
    }
    @After fun tearDown() { player.release() }

    @Test fun unchangedOccurrenceIsAcceptedAndBadIndicesAreRefused() {
        val stamp = stamp()
        assertTrue(queueEntryCurrent(player, stamp, revision, 1, a))
        assertFalse(queueEntryCurrent(player, stamp, revision, -1, a))
        assertFalse(queueEntryCurrent(player, stamp, revision, 3, a))
        assertFalse(queueEntryCurrent(player, stamp, revision, 1, b))
    }

    @Test fun shrinkRejectsAnOldIndexWithoutReadingIt() {
        val stamp = stamp()
        player.removeMediaItems(1, 3)
        assertFalse(queueEntryCurrent(player, stamp, revision, 2, b))
    }

    @Test fun replacedQueueWithSameDuplicateIdsIsStillANewSnapshot() {
        val stamp = stamp()
        player.setMediaItems(listOf(a, a, b), 0, 0L)
        assertEquals(a, player.getMediaItemAt(1))
        assertFalse(queueEntryCurrent(player, stamp, revision, 1, a))
    }

    @Test fun transitionAndShuffleChangeInvalidateOldInput() {
        val stamp = stamp()
        player.seekToDefaultPosition(1)
        assertFalse(queueEntryCurrent(player, stamp, revision, 2, b))
        val current = stamp()
        player.shuffleModeEnabled = true
        assertFalse(queueEntryCurrent(player, current, revision, 2, b))
    }

    @Test fun revisionInvalidatesEvenWhenTimelineAndItemAreUnchanged() {
        val stamp = stamp()
        revision++
        assertFalse(queueEntryCurrent(player, stamp, revision, 1, a))
        assertTrue(queueEntryCurrent(player, stamp(), revision, 1, a))
    }

    private fun stamp() = QueueActionStamp(revision, player.currentTimeline,
        player.currentMediaItemIndex, player.shuffleModeEnabled)
    private fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("http://192.168.1.10:7814/api1/file/42").build()
}
