package dev.avery.muon

import android.os.Bundle
import android.os.Process
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Real production callback + pinned Media3 default set-items delegation; no audio or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PlaybackSessionCallbackTest {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val callback = PlaybackSessionCallback()

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication<android.app.Application>()
        player = ExoPlayer.Builder(context).build()
        session = MediaSession.Builder(context, player).setCallback(callback).build()
    }

    @After fun tearDown() {
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
    }

    @Test fun ownUidKeepsFullCommandsEvenWithDifferentPackageLabel() {
        val result = callback.onConnectAsync(session,
            controller(uid = Process.myUid(), trusted = false, packageName = "own-process-label")).get()
        assertTrue(result.isAccepted)
        assertEquals(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS, result.availablePlayerCommands)
    }

    @Test fun foreignUidWithAppPackageLabelIsRejectedWhenUntrusted() {
        val result = callback.onConnectAsync(session, controller(trusted = false,
            packageName = RuntimeEnvironment.getApplication<android.app.Application>().packageName)).get()
        assertFalse(result.isAccepted)
    }

    @Test fun trustedExternalHasTransportAndReadCommandsButNoQueueOrConfigurationEdits() {
        val result = callback.onConnectAsync(session, controller()).get()
        assertTrue(result.isAccepted)
        assertEquals(SessionCommands.EMPTY, result.availableSessionCommands)
        val commands = result.availablePlayerCommands
        listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA).forEach { assertTrue("Required command $it", commands.contains(it)) }
        listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_PLAYLIST_METADATA, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_AUDIO_ATTRIBUTES,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_RELEASE
        ).forEach { assertFalse("Disallowed command $it", commands.contains(it)) }
    }

    @Test fun trustedForeignUidWithAppLabelStillCannotSupplyQueueItems() {
        val foreign = controller(packageName = RuntimeEnvironment.getApplication<android.app.Application>().packageName)
        assertFalse(callback.onConnectAsync(session, foreign).get().availablePlayerCommands
            .contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFailure(callback.onAddMediaItems(session, foreign, mutableListOf(item(3))),
            UnsupportedOperationException::class.java)
    }

    @Test fun unknownUidLegacyCallerNeverGetsOwnAppQueueAccess() {
        val legacy = controller(uid = -1)
        assertFalse(callback.onConnectAsync(session, legacy).get().availablePlayerCommands
            .contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFailure(callback.onAddMediaItems(session, legacy, mutableListOf(item(3))),
            UnsupportedOperationException::class.java)
    }

    @Test fun externalAddFailsInsteadOfReturningSuccessfulEmptyList() {
        assertFailure(callback.onAddMediaItems(session, controller(), mutableListOf(item(3))),
            UnsupportedOperationException::class.java)
        assertFailure(callback.onAddMediaItems(session, controller(), mutableListOf()),
            UnsupportedOperationException::class.java)
    }

    @Test fun defaultSetItemsPropagatesFailureWithoutClearingExistingQueue() {
        player.setMediaItems(listOf(item(1), item(2)), 1, 1234)
        for (request in listOf(mutableListOf(item(3)), mutableListOf())) {
            // Use Media3's real default method, including its onAddMediaItems delegation. Only a
            // successful resolution is eligible for the subsequent setMediaItems in Media3.
            val resolved = callback.onSetMediaItems(session, controller(), request, C.INDEX_UNSET, C.TIME_UNSET)
            assertFailure(resolved, UnsupportedOperationException::class.java)
            assertEquals(2, player.mediaItemCount)
            assertEquals("2", player.currentMediaItem?.mediaId)
            assertEquals(1234, player.currentPosition)
        }
    }

    @Test fun ownAppCanResolveQueueAndClearItIntentionally() {
        val own = controller(uid = Process.myUid())
        val items = mutableListOf(item(1), item(2))
        val resolved = callback.onSetMediaItems(session, own, items, 1, 1234).get()
        assertEquals(items, resolved.mediaItems)
        assertEquals(1, resolved.startIndex)
        assertEquals(1234, resolved.startPositionMs)
        assertTrue(callback.onSetMediaItems(session, own, mutableListOf(), C.INDEX_UNSET, C.TIME_UNSET)
            .get().mediaItems.isEmpty())
    }

    @Test fun ownAppStillCannotResolveInvalidStreamUris() {
        val own = controller(uid = Process.myUid())
        for (uri in listOf("file:///tmp/song.mp3", "http://192.168.1.2:7814/other/3",
            "http://192.168.1.2:7814/api1/file/3?url=other", "http://192.168.1.2:7814/api1/file/3#fragment")) {
            assertFailure(callback.onSetMediaItems(session, own,
                mutableListOf(MediaItem.fromUri(uri)), 0, 0), IllegalArgumentException::class.java)
        }
        assertFailure(callback.onAddMediaItems(session, own,
            mutableListOf(MediaItem.Builder().setMediaId("unresolved").build())), IllegalArgumentException::class.java)
    }

    private fun item(id: Int) = MediaItem.Builder().setMediaId(id.toString())
        .setUri("http://192.168.1.2:7814/api1/file/$id").build()

    private fun controller(
        uid: Int = Process.myUid() + 1,
        trusted: Boolean = true,
        packageName: String = "external.controller",
    ) = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        packageName, Process.myPid(), uid, 0, 0, trusted, Bundle.EMPTY, true,
    )

    private fun assertFailure(future: ListenableFuture<*>, cause: Class<out Throwable>) {
        val failure = assertThrows(ExecutionException::class.java) { future.get(1, TimeUnit.SECONDS) }
        assertTrue("Expected ${cause.simpleName}, got ${failure.cause}", cause.isInstance(failure.cause))
    }
}
