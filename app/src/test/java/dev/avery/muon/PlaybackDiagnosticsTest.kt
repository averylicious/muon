package dev.avery.muon

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackDiagnosticsTest {
    @Test fun normalCanaryStableAndBenchmarkNeverEnablePlaybackLogging() {
        for (name in listOf("dev.avery.muon", "dev.avery.muon.release", "dev.avery.muon.benchmark"))
            for (debug in listOf(false, true)) assertFalse(playbackDiagnosticsEnabled(name, debug))
        assertFalse(playbackDiagnosticsEnabled("dev.avery.muon.diagnostic", false))
        assertTrue(playbackDiagnosticsEnabled("dev.avery.muon.diagnostic", true))
        val app = RuntimeEnvironment.getApplication()
        val player = ExoPlayer.Builder(app).build()
        try {
            for (name in listOf("dev.avery.muon", "dev.avery.muon.release", "dev.avery.muon.benchmark")) {
                // The hosting test APK may itself be Diagnostic. Explicitly simulate each ordinary
                // package, even with its debug flag set, rather than assuming the fixture's identity.
                val context = object : ContextWrapper(app) {
                    override fun getPackageName() = name
                    override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply {
                        flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
                    }
                }
                assertNull(observePlaybackDiagnostics(context, player))
            }
        } finally { player.release() }
    }

    @Test fun exhaustedBudgetStopsSamplingAndSinkFailureDoesNotEscape() {
        val lines = mutableListOf<String>()
        var samples = 0
        val budget = PlaybackDiagnosticBudget(2, lines::add)
        repeat(20) { budget.record(PlaybackDiagnosticEvent.SAMPLE) { samples++; "positionMs=0" } }
        assertEquals(2, samples)
        assertEquals(2, lines.size)
        assertTrue(lines.last().endsWith("captureLimitReached=true"))
        assertFalse(budget.available)
        val broken = PlaybackDiagnosticBudget(1) { error("Unavailable log sink") }
        broken.record(PlaybackDiagnosticEvent.STATE) { "state=2" }
        assertFalse(broken.available)
    }

    @Test fun diagnosticListenerEmitsScalarsWithoutSongOrRequestTextAndDetachesOnClose() {
        ShadowLog.clear()
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getPackageName() = "dev.avery.muon.diagnostic"
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply {
                flags = flags or ApplicationInfo.FLAG_DEBUGGABLE
            }
        }
        val player = ExoPlayer.Builder(app).build()
        val observer = requireNotNull(observePlaybackDiagnostics(context, player))
        fun lines() = ShadowLog.getLogsForTag("MuonPlayback").map { it.msg }
        try {
            player.setMediaItem(MediaItem.Builder().setMediaId("private-song-identifier")
                .setUri("http://private-server/secret-path?token=never-print")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("private-title").setArtist("private-artist").build()).build())
            player.playWhenReady = true
            shadowOf(player.applicationLooper).idle()
            assertTrue(lines().any { "route=LIVE" in it && "wanted=true" in it })
            val text = lines().joinToString("\n")
            for (secret in listOf("private", "http", "secret-path", "never-print", "token=")) assertFalse(text.contains(secret))
            observer.close()
            val count = lines().size
            player.playWhenReady = false
            shadowOf(player.applicationLooper).idle()
            assertEquals(count, lines().size)
            observer.close()
        } finally { observer.close(); player.release() }
    }

    @Test fun failuresUseFixedCategoriesRatherThanMessagesOrExceptionNames() {
        assertEquals(PlaybackDiagnosticFailure.TIMEOUT, playbackDiagnosticFailure(IOException("private URL", SocketTimeoutException("secret"))))
        assertEquals(PlaybackDiagnosticFailure.DNS, playbackDiagnosticFailure(UnknownHostException("private-server")))
        assertEquals(PlaybackDiagnosticFailure.IO, playbackDiagnosticFailure(IOException("private-path")))
        val loop = object : IOException("private") { override val cause: Throwable get() = this }
        assertEquals(PlaybackDiagnosticFailure.IO, playbackDiagnosticFailure(loop))
    }
}
