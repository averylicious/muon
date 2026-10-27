package dev.avery.muon

import android.os.Bundle
import android.os.Process
import android.os.Parcel
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaController
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
    private val connected = mutableListOf<MediaController>()

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        player = ExoPlayer.Builder(context).build()
        session = MediaSession.Builder(context, player).setCallback(callback).build()
    }

    @After fun tearDown() {
        connected.asReversed().forEach { it.release() }
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
    }

    @Test fun realNotificationConnectionRestrictsThePlatformBridgeWhileTheUiKeepsQueueEdits() {
        // Command availability is intersected with the actual player state. Supply a timeline,
        // without prepare/play/network, before comparing transport and content-edit capabilities.
        player.setMediaItems(listOf(item(1), item(2)))
        val context = RuntimeEnvironment.getApplication()
        val hints = Bundle().apply { putBoolean(MediaController.KEY_MEDIA_NOTIFICATION_CONTROLLER_FLAG, true) }
        fun connect(hints: Bundle): MediaController {
            val future = MediaController.Builder(context, session.token).setConnectionHints(hints).buildAsync()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!future.isDone) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    check(System.nanoTime() < deadline) { "The real controller did not connect" }
                    Thread.sleep(1) // Yield to Media3's handler; not a latency assertion.
                }
                return future.get().also {
                    connected += it
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                }
            } finally { if (!future.isDone) future.cancel(false) }
        }
        val ui = connect(Bundle.EMPTY)
        val notification = connect(hints)
        assertTrue(ui.availableCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(ui.availableCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(ui.availableCommands.contains(Player.COMMAND_SET_VOLUME))
        for (command in listOf(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_SET_VOLUME, Player.COMMAND_SET_SPEED_AND_PITCH)) {
            assertFalse(notification.availableCommands.contains(command))
        }
        assertTrue("Notification lost play/pause on the seeded timeline",
            notification.availableCommands.contains(Player.COMMAND_PLAY_PAUSE))
        val internal = MediaSession.ControllerInfo.createTestOnlyControllerInfo(context.packageName,
            Process.myPid(), Process.myUid(), MediaLibraryInfo.VERSION_INT, 0, true, hints, true)
        assertTrue(session.isMediaNotificationController(internal))
        val grant = callback.onConnectAsync(session, internal).get()
        assertEquals(SessionCommands.EMPTY, grant.availableSessionCommands)
        assertFailure(callback.onAddMediaItems(session, internal, mutableListOf(item(1))), UnsupportedOperationException::class.java)
        for (command in listOf(REMOVE_QUEUE_ITEM, RESTORE_QUEUE_REMOVAL))
            assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED,
                callback.onCustomCommand(session, internal, command, Bundle.EMPTY).get().resultCode)
        // Exercise the pinned framework path after its real notification connection. Synthetic
        // ControllerInfo does NOT prove external token access, package visibility or impersonation.
        val impl = MediaSession::class.java.getDeclaredField("impl").apply { isAccessible = true }.get(session)
        val onConnect = impl.javaClass.getDeclaredMethod("onConnectOnHandler", MediaSession.ControllerInfo::class.java)
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val platform = (onConnect.invoke(impl, controller(trusted = false, packageName = "com.android.systemui"))
            as ListenableFuture<MediaSession.ConnectionResult>).get(1, TimeUnit.SECONDS)
        assertTrue(platform.isAccepted)
        assertEquals(grant.availablePlayerCommands, platform.availablePlayerCommands)
        assertEquals(SessionCommands.EMPTY, platform.availableSessionCommands)
        assertFalse(platform.availablePlayerCommands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertTrue(callback.onConnectAsync(session, controller(uid = Process.myUid())).get()
            .availableSessionCommands.contains(RESTORE_QUEUE_REMOVAL))
    }

    @Test fun ownUidKeepsFullCommandsEvenWithDifferentPackageLabel() {
        val result = callback.onConnectAsync(session,
            controller(uid = Process.myUid(), trusted = false, packageName = "own-process-label")).get()
        assertTrue(result.isAccepted)
        assertEquals(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS, result.availablePlayerCommands)
    }

    @Test fun queueRemovalAndUndoCommandsAreOwnUidOnlyAndRejectMalformedRequests() {
        val own = controller(uid = Process.myUid())
        val endpoint = ServerEndpoint.parse("http://192.168.1.2:7814")
        player.setMediaItems(listOf(1L, 2L).map { TauonTrack(it, "S", "A", "B", 1000L, true, false).mediaItem(endpoint) })
        val second = player.getMediaItemAt(1)
        // A valid removal request; both commands must check the caller before parsing their payload.
        val valid = QueueRemovalRequest.capture(player, 1, second).toBundle()
        for (command in listOf(REMOVE_QUEUE_ITEM, RESTORE_QUEUE_REMOVAL)) {
            assertTrue(callback.onConnectAsync(session, own).get().availableSessionCommands.contains(command))
            assertFalse(callback.onConnectAsync(session, controller()).get().availableSessionCommands.contains(command))
            assertEquals(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED,
                callback.onCustomCommand(session, controller(), command, valid).get().resultCode)
            assertEquals(androidx.media3.session.SessionError.ERROR_BAD_VALUE,
                callback.onCustomCommand(session, own, command, Bundle.EMPTY).get().resultCode)
        }
        assertEquals(listOf(1L, 2L).map { TauonTrack(it, "S", "A", "B", 1000L, true, false).mediaItem(endpoint).mediaId },
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
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
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS, Player.COMMAND_GET_TRACKS, Player.COMMAND_RELEASE
        ).forEach { assertFalse("Disallowed command $it", commands.contains(it)) }
    }

    @Test fun externalGrantFiltersRawTracksBeforePinnedRemoteBundlingButKeepsSongMetadata() {
        // Pinned PlayerInfo is package-private. Reflection is test-only; connect and update both
        // use this filter before remote bundling. No Binder transaction or phone is exercised.
        val type = Class.forName("androidx.media3.session.PlayerInfo")
        val builderType = Class.forName("androidx.media3.session.PlayerInfo\$Builder")
        val initial = type.getDeclaredField("DEFAULT").apply { isAccessible = true }.get(null)
        val builder = builderType.getDeclaredConstructor(type).apply { isAccessible = true }.newInstance(initial)
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup(Format.Builder()
            .setSampleMimeType("audio/raw").setLabel("L".repeat(256 * 1024)).build()),
            false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        val metadata = MediaMetadata.Builder().setTitle("Song").setArtist("Artist").build()
        builderType.getDeclaredMethod("setCurrentTracks", Tracks::class.java).apply { isAccessible = true }.invoke(builder, tracks)
        builderType.getDeclaredMethod("setMediaMetadata", MediaMetadata::class.java).apply { isAccessible = true }.invoke(builder, metadata)
        val state = builderType.getDeclaredMethod("build").apply { isAccessible = true }.invoke(builder)
        val filter = type.getDeclaredMethod("filterByAvailableCommands", Player.Commands::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val bundle = type.getDeclaredMethod("toBundleForRemoteProcess", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val commands = callback.onConnectAsync(session, controller()).get().availablePlayerCommands
        val filtered = filter.invoke(state, commands, false, false)
        assertEquals(Tracks.EMPTY, type.getDeclaredField("currentTracks").apply { isAccessible = true }.get(filtered))
        assertEquals(metadata, type.getDeclaredField("mediaMetadata").apply { isAccessible = true }.get(filtered))
        val own = callback.onConnectAsync(session, controller(uid = Process.myUid())).get().availablePlayerCommands
        val ownState = filter.invoke(state, own, false, false)
        assertEquals(tracks, type.getDeclaredField("currentTracks").apply { isAccessible = true }.get(ownState))
        fun size(value: Any): Int {
            val parcel = Parcel.obtain()
            return try {
                parcel.writeBundle(bundle.invoke(value, MediaLibraryInfo.INTERFACE_VERSION) as Bundle)
                parcel.dataSize()
            } finally { parcel.recycle() }
        }
        assertTrue(size(ownState) > 256 * 1024)
        assertTrue(size(filtered) < 64 * 1024)
        // Only the raw-track route is removed. Oversized optional metadata text remains #738.
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

    @Test fun replacementCallbacksShareAdmissionUntilCancelledAndActiveWorkersReallyExit() {
        val oldWorker = HeldAdmission()
        val newWorker = HeldAdmission()
        val own = controller(uid = Process.myUid())
        val futures = mutableListOf<ListenableFuture<MutableList<MediaItem>>>()
        var oldReads = 0; var newReads = 0
        val old = PlaybackSessionCallback({ oldReads++; true }, oldWorker)
        fun savedRequest(callback: PlaybackSessionCallback, name: String) =
            callback.onAddMediaItems(session, own, mutableListOf(saved(name))).also(futures::add)
        val replacement = PlaybackSessionCallback({
            newReads++
            // A different callback may not steal the shared slot even while this one is active.
            assertFailure(savedRequest(old, "during-active-read"), IllegalStateException::class.java)
            assertTrue(oldWorker.tasks.isEmpty())
            true
        }, newWorker)
        try {
            val abandoned = savedRequest(old, "old-owner")
            assertTrue(abandoned.cancel(false))
            assertFailure(savedRequest(old, "same-owner"), IllegalStateException::class.java)
            assertFailure(savedRequest(replacement, "new-owner"), IllegalStateException::class.java)
            assertEquals(1, oldWorker.tasks.size)
            assertTrue(newWorker.tasks.isEmpty())
            // An old saved-source check must not prevent a live-only queue from being admitted.
            assertEquals(1, replacement.onAddMediaItems(session, own, mutableListOf(item(7))).get().size)
            oldWorker.next()
            assertEquals(0, oldReads) // Cancellation was observed before touching storage.
            val next = savedRequest(replacement, "after-old-exit")
            assertEquals(1, newWorker.tasks.size)
            newWorker.next()
            assertEquals(1, next.get().size)
            assertEquals(1, newReads)
            val retry = savedRequest(old, "after-replacement-exit")
            oldWorker.next()
            assertEquals(1, retry.get().size)
            assertEquals(1, oldReads)
        } finally {
            // Cancel before draining controlled workers even on assertion failure: no fake pending
            // task may leave the process admission held for another test. No production reset hook.
            futures.forEach { it.cancel(false) }
            while (oldWorker.tasks.isNotEmpty()) oldWorker.next()
            while (newWorker.tasks.isNotEmpty()) newWorker.next()
        }
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
        val timing = SavedStartupTiming(true, { ++now }, emit=events::add)
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

    @Test fun brokenDiagnosticClocksOrSinkCannotStrandActualSavedAdmission() {
        val own=controller(uid=Process.myUid())
        for(failure in listOf("begin","end","sink")) {
            var clocks=0;var reads=0
            val timing=SavedStartupTiming(true,clock={
                clocks++
                if(failure=="begin" || failure=="end" && clocks%2==0) throw IllegalStateException("Diagnostic clock unavailable")
                clocks.toLong()
            },emit={if(failure=="sink") throw IllegalStateException("Diagnostic sink unavailable")})
            val worker=HeldAdmission()
            val accepted=PlaybackSessionCallback({reads++;true},worker,timing)
            repeat(2) {
                val future=accepted.onAddMediaItems(session,own,mutableListOf(saved()))
                worker.next() // Run outside submission: asynchronous finally failures cannot reach its catch.
                val result=future.get(1,TimeUnit.SECONDS)
                assertEquals(1,result.size)
            }
            assertEquals(2,reads) // A diagnostic failure must not leave admissionPending held.
        }
    }

}
