package dev.avery.muon

import android.content.ComponentName
import android.os.SystemClock
import android.app.NotificationManager
import androidx.media3.common.C
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Requires a real Tauon instance and a real local file. No fake audio or mock HTTP server. */
@RunWith(AndroidJUnit4::class)
class DirectPlaybackTest {
    @Test fun directStreamDecodesAndSeeks() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val endpoint = ServerEndpoint.parse(requireNotNull(args.getString("tauonOrigin")))
        val trackId = requireNotNull(args.getString("trackId")).toLong()
        val api = TauonApi(endpoint)
        val track = runBlocking {
            api.connect()
            api.playlists().asSequence().map { runBlocking { api.tracks(it.id) } }.flatten().first { it.id == trackId }
        }
        assertTrue(track.playable)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var controller: MediaController? = null
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            inst.runOnMainSync {
                future = MediaController.Builder(inst.targetContext, SessionToken(inst.targetContext,
                    ComponentName(inst.targetContext, PlaybackService::class.java))).buildAsync()
            }
            controller = future!!.get(15, TimeUnit.SECONDS)
            val player = controller!!
            fun await(label: String, predicate: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 30000
                while (SystemClock.elapsedRealtime() < deadline) {
                    var done = false
                    inst.runOnMainSync { player.playerError?.let { throw AssertionError(label, it) }; done = predicate() }
                    if (done) return
                    SystemClock.sleep(100)
                }
                fail("Timed out: $label")
            }
            try {
                inst.runOnMainSync { player.setMediaItem(track.mediaItem(endpoint)); player.prepare(); player.play() }
                await("decoded playback progress") { player.isPlaying && player.currentPosition > 1500 }
                var target = 0L
                inst.runOnMainSync {
                    assertTrue("Must be seekable", player.isCurrentMediaItemSeekable)
                    assertTrue("Audio track selected", player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
                    assertTrue(player.duration > 5000)
                    target = player.duration / 2
                    player.pause(); player.seekTo(target); player.play()
                }
                await("post-seek playback") { player.playbackState == Player.STATE_READY && player.isPlaying &&
                    player.currentPosition in (target+1500)..(target+15000) }
                inst.runOnMainSync {
                    Log.i("MuonProof", "PASS direct playback + seek; id=$trackId position=${player.currentPosition} duration=${player.duration}")
                    player.pause()
                }
                await("pause") { !player.isPlaying }
                inst.runOnMainSync { player.play() }
                await("resume") { player.isPlaying }
                // A removed/unavailable file must fail visibly without killing the service.
                inst.runOnMainSync {
                    player.setMediaItem(track.copy(id = Long.MAX_VALUE).mediaItem(endpoint))
                    player.prepare(); player.play()
                }
                val errorDeadline = SystemClock.elapsedRealtime() + 15000
                var errorCode: Int? = null
                while (errorCode == null && SystemClock.elapsedRealtime() < errorDeadline) {
                    inst.runOnMainSync { errorCode = player.playerError?.errorCode }
                    SystemClock.sleep(100)
                }
                assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, errorCode)
                inst.runOnMainSync { player.setMediaItem(track.mediaItem(endpoint)); player.prepare(); player.play() }
                await("recovery after unavailable file") { player.isPlaying && player.currentPosition > 500 }
                Log.i("MuonProof", "PASS HTTP 404 error and subsequent playback recovery")
                val other = runBlocking {
                    var found: TauonTrack? = null
                    for (playlist in api.playlists()) {
                        found = api.tracks(playlist.id).firstOrNull { it.playable && it.id != trackId }
                        if (found != null) break
                    }
                    requireNotNull(found) { "Need a second playable track for queue proof" }
                }
                inst.runOnMainSync {
                    player.setMediaItems(listOf(track.mediaItem(endpoint), other.mediaItem(endpoint)), 0, 0)
                    player.prepare(); player.play()
                }
                await("queue start") { player.isPlaying && player.currentPosition > 500 }
                inst.runOnMainSync { player.seekToNextMediaItem() }
                await("next track") { player.currentMediaItemIndex == 1 && player.isPlaying && player.currentPosition > 500 }
                inst.runOnMainSync { player.seekToPreviousMediaItem() }
                await("previous track") { player.currentMediaItemIndex == 0 && player.isPlaying && player.currentPosition > 500 }
                var before = 0L
                inst.runOnMainSync { before = player.currentPosition }
                scenario.onActivity { it.moveTaskToBack(true) }
                await("background playback") { player.isPlaying && player.currentPosition > before + 1500 }
                val notifications = inst.targetContext.getSystemService(NotificationManager::class.java).activeNotifications
                assertTrue("Media notification exists", notifications.any { it.notification.category == "transport" })
                Log.i("MuonProof", "PASS next/previous, background progress and media notification")
            } finally {
                inst.runOnMainSync { player.stop(); player.clearMediaItems(); player.release() }
            }
        }
    }
}
