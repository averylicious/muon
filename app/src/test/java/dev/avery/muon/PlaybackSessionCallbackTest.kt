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
        val context = RuntimeEnvironment.getApplication()
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
            packageName = RuntimeEnvironment.getApplication().packageName)).get()
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
        val foreign = controller(packageName = RuntimeEnvironment.getApplication().packageName)
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
        for (uri in listOf("file://localhost/tmp/song.mp3", "http://192.168.1.2:7814/other/3",
            "http://192.168.1.2:7814/api1/file/3?url=other", "http://192.168.1.2:7814/api1/file/3#fragment")) {
            assertFailure(callback.onSetMediaItems(session, own,
                mutableListOf(MediaItem.fromUri(uri)), 0, 0), IllegalArgumentException::class.java)
        }
        assertFailure(callback.onAddMediaItems(session, own,
            mutableListOf(MediaItem.Builder().setMediaId("unresolved").build())), IllegalArgumentException::class.java)
    }

    private class HeldAdmission : java.util.concurrent.Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun next() { tasks.removeFirst().run() }
    }

    private fun saved(id: String = "one"): MediaItem {
        val handle = requireNotNull(SavedRef.download(SavedShelf.Phone, "saved/$id", "saved/$id")).handle
        return MediaItem.Builder().setMediaId(handle).setUri(handle).build()
    }

    @Test fun anOversizedQueueFailsBeforeSavedReadsAndDoesNotClearCurrentPlayback() {
        player.setMediaItems(listOf(item(1), item(2)), 1, 1234)
        val bounded = PlaybackSessionCallback({ fail("No saved IO after size refusal"); false },
            java.util.concurrent.Executor { fail("No task after size refusal") })
        assertFailure(bounded.onSetMediaItems(session, controller(uid = Process.myUid()),
            MutableList(2_049) { saved() }, 0, 0), IllegalArgumentException::class.java)
        assertEquals(2, player.mediaItemCount)
        assertEquals("2", player.currentMediaItem?.mediaId)
        assertEquals(1234, player.currentPosition)
    }

    @Test fun repeatedSavedChecksRetainOnlyOneWorkerAndRetryAfterItCompletes() {
        val worker = HeldAdmission(); var reads = 0
        val bounded = PlaybackSessionCallback({ reads++; true }, worker)
        val own = controller(uid = Process.myUid())
        val first = bounded.onAddMediaItems(session, own, mutableListOf(saved()))
        repeat(100) {
            assertFailure(bounded.onAddMediaItems(session, own, mutableListOf(saved("other"))), IllegalStateException::class.java)
        }
        assertEquals(1, worker.tasks.size); assertFalse(first.isDone); assertEquals(0, reads)
        worker.next(); assertEquals(1, first.get().size); assertEquals(1, reads)
        val retry = bounded.onAddMediaItems(session, own, mutableListOf(saved("retry")))
        worker.next(); assertEquals(1, retry.get().size); assertEquals(2, reads)
    }

    @Test fun cancellationDoesNotFreeAStillQueuedWorkerSlot() {
        val worker = HeldAdmission(); var reads = 0
        val bounded = PlaybackSessionCallback({ reads++; true }, worker)
        val own = controller(uid = Process.myUid())
        val first = bounded.onAddMediaItems(session, own, mutableListOf(saved()))
        assertTrue(first.cancel(false))
        assertFailure(bounded.onAddMediaItems(session, own, mutableListOf(saved("retry"))), IllegalStateException::class.java)
        assertEquals(1, worker.tasks.size)
        worker.next(); assertEquals(0, reads)
        val retry = bounded.onAddMediaItems(session, own, mutableListOf(saved("retry")))
        worker.next(); assertEquals(1, retry.get().size); assertEquals(1, reads)
    }

    @Test fun callerMutationCannotChangeTheHeldQueueOrderOrDropItsCheckedCopies() {
        val worker = HeldAdmission(); val checked = ArrayList<String>()
        val bounded = PlaybackSessionCallback({ checked += it.requestId; true }, worker)
        val incoming = mutableListOf(saved("first"), saved("second"))
        val expected = incoming.toList()
        val future = bounded.onAddMediaItems(session, controller(uid = Process.myUid()), incoming)
        incoming.clear(); incoming += saved("unchecked")
        worker.next()
        assertEquals(expected, future.get()); assertEquals(listOf("saved/first", "saved/second"), checked)
    }

    @Test fun savedReadFailureReleasesAdmissionForAnExplicitRetry() {
        val worker = HeldAdmission(); var allow = false
        val bounded = PlaybackSessionCallback({ allow }, worker)
        val own = controller(uid = Process.myUid())
        val bad = bounded.onAddMediaItems(session, own, mutableListOf(saved()))
        worker.next(); assertFailure(bad, IllegalArgumentException::class.java)
        allow = true
        val retry = bounded.onAddMediaItems(session, own, mutableListOf(saved()))
        worker.next(); assertEquals(1, retry.get().size)
    }

    @Test fun executorRejectionDoesNotLeaveTheAdmissionPermanentlyBusy() {
        val worker = HeldAdmission(); var reject = true
        val bounded = PlaybackSessionCallback({ true }, java.util.concurrent.Executor {
            if (reject) throw java.util.concurrent.RejectedExecutionException() else worker.execute(it)
        })
        val own = controller(uid = Process.myUid())
        assertFailure(bounded.onAddMediaItems(session, own, mutableListOf(saved())),
            java.util.concurrent.RejectedExecutionException::class.java)
        reject = false
        val retry = bounded.onAddMediaItems(session, own, mutableListOf(saved()))
        worker.next(); assertEquals(1, retry.get().size)
    }

    @Test fun activeCancellationStopsFurtherSavedReadsAndKeepsItsSlotUntilTheTaskReturns() {
        val worker = HeldAdmission(); val own = controller(uid = Process.myUid()); var reads = 0
        lateinit var bounded: PlaybackSessionCallback
        lateinit var future: ListenableFuture<MutableList<MediaItem>>
        bounded = PlaybackSessionCallback({
            reads++; assertTrue(future.cancel(false))
            assertFailure(bounded.onAddMediaItems(session, own, mutableListOf(saved("nested"))), IllegalStateException::class.java)
            true
        }, worker)
        future = bounded.onAddMediaItems(session, own, mutableListOf(saved("first"), saved("second")))
        worker.next(); assertEquals(1, reads); assertTrue(future.isCancelled); assertTrue(worker.tasks.isEmpty())
    }

    @Test fun countBoundaryPreservesAllDuplicatesAndChecksTheirSourceOnlyOnce() {
        val worker = HeldAdmission(); var reads = 0
        val bounded = PlaybackSessionCallback({ reads++; true }, worker)
        val input = MutableList(2_048) { saved() }
        val future = bounded.onAddMediaItems(session, controller(uid = Process.myUid()), input)
        worker.next(); assertEquals(input, future.get()); assertEquals(2_048, future.get().size); assertEquals(1, reads)
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
    @Test fun diagnosticAdmissionTimesTheRealWorkerAndItsCancelledOrRefusedResult() {
        var now = 0L
        val events = ArrayList<SavedStartupTiming.Event>()
        val timing = SavedStartupTiming(true, { ++now }, events::add)
        val own = controller(uid = Process.myUid())
        val accepted = PlaybackSessionCallback({ true }, java.util.concurrent.Executor { it.run() }, timing)
        assertEquals(1, accepted.onAddMediaItems(session, own, mutableListOf(saved())).get().size)
        assertEquals(SavedStartupTiming.Outcome.OK, events.last().outcome)
        val refused = PlaybackSessionCallback({ false }, java.util.concurrent.Executor { it.run() }, timing)
        assertFailure(refused.onAddMediaItems(session, own, mutableListOf(saved())), IllegalArgumentException::class.java)
        assertEquals(SavedStartupTiming.Outcome.FAILED, events.last().outcome)
        val held = HeldAdmission()
        val cancelled = PlaybackSessionCallback({ fail("Cancelled read must not run"); false }, held, timing)
        val future = cancelled.onAddMediaItems(session, own, mutableListOf(saved()))
        future.cancel(false); held.next()
        assertEquals(SavedStartupTiming.Outcome.CANCELLED, events.last().outcome)
        assertEquals(3, events.size)
        assertTrue(events.all { it.phase == SavedStartupTiming.Phase.ADMISSION && it.milliseconds >= 0 })
    }

}
