package dev.avery.muon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** No URLs, titles, IDs, exception text, response headers or persistent files. Diagnostic only. */
internal fun playbackDiagnosticsEnabled(packageName: String, debuggable: Boolean): Boolean =
    packageName == "dev.avery.muon.diagnostic" && debuggable

internal enum class PlaybackDiagnosticEvent { ATTACHED, STATE, SAMPLE, LOAD_START, LOAD_END, LOAD_CANCEL, LOAD_ERROR }
internal enum class PlaybackDiagnosticRoute { NONE, LIVE, SAVED }
internal enum class PlaybackDiagnosticNetwork { NONE, WIFI, CELLULAR, ETHERNET, VPN, OTHER, UNKNOWN }
internal enum class PlaybackDiagnosticFailure { NONE, TIMEOUT, DNS, IO }

internal fun playbackDiagnosticFailure(error: Throwable): PlaybackDiagnosticFailure {
    var cause: Throwable? = error
    // A hostile/broken exception chain cannot make logging loop forever.
    repeat(8) {
        when (cause) {
            is SocketTimeoutException -> return PlaybackDiagnosticFailure.TIMEOUT
            is UnknownHostException -> return PlaybackDiagnosticFailure.DNS
        }
        cause = cause?.cause
    }
    return PlaybackDiagnosticFailure.IO
}

/** Fixed-size observations only. The sink and clock may fail without affecting playback. */
internal class PlaybackDiagnosticBudget(private val maximum: Int = 1024, private val emit: (String) -> Unit) {
    private var emitted = 0
    val available: Boolean get() = emitted < maximum
    fun record(event: PlaybackDiagnosticEvent, fields: () -> String) {
        if (!available) return
        emitted++
        runCatching { emit("event=$event ${fields()}" + if (!available) " captureLimitReached=true" else "") }
    }
}

/**
 * Passive Media3 observations for #671. Only the separately installed, debuggable Diagnostic app
 * attaches this listener. A sample every five seconds while buffering distinguishes a quiet retry,
 * suppressed/paused player and changing buffer/position without requesting URLs or logging songs.
 * Never retries, seeks, changes timeouts/volume, or sends a network probe. At most 1024 lines per
 * service lifetime; reopening playback after restarting Diagnostic gives a fresh capture budget.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun observePlaybackDiagnostics(context: Context, player: ExoPlayer): AutoCloseable? {
    if (!playbackDiagnosticsEnabled(context.packageName,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)) return null
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val handler = Handler(player.applicationLooper)
    val log = PlaybackDiagnosticBudget { android.util.Log.d("MuonPlayback", it) }
    var closed = false
    fun network(): PlaybackDiagnosticNetwork = runCatching {
        val active = connectivity?.activeNetwork ?: return@runCatching PlaybackDiagnosticNetwork.NONE
        val caps = connectivity.getNetworkCapabilities(active) ?: return@runCatching PlaybackDiagnosticNetwork.UNKNOWN
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> PlaybackDiagnosticNetwork.VPN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> PlaybackDiagnosticNetwork.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PlaybackDiagnosticNetwork.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> PlaybackDiagnosticNetwork.ETHERNET
            else -> PlaybackDiagnosticNetwork.OTHER
        }
    }.getOrDefault(PlaybackDiagnosticNetwork.UNKNOWN)
    fun report(event: PlaybackDiagnosticEvent, load: LoadEventInfo? = null, retry: Int = 0,
        failure: PlaybackDiagnosticFailure = PlaybackDiagnosticFailure.NONE) {
        if (closed) return
        log.record(event) {
            val route = player.currentMediaItem?.mediaId?.let {
                if (isSavedHandle(it)) PlaybackDiagnosticRoute.SAVED else PlaybackDiagnosticRoute.LIVE
            } ?: PlaybackDiagnosticRoute.NONE
            "elapsedMs=${SystemClock.elapsedRealtime()} state=${player.playbackState} " +
                "wanted=${player.playWhenReady} playing=${player.isPlaying} loading=${player.isLoading} " +
                "suppression=${player.playbackSuppressionReason} route=$route network=${network()} " +
                "positionMs=${player.currentPosition} bufferedMs=${player.totalBufferedDuration} " +
                "playerError=${player.playerError?.errorCode ?: 0} " +
                "loadMs=${load?.loadDurationMs ?: 0} loadBytes=${load?.bytesLoaded ?: 0} retry=$retry failure=$failure"
        }
    }
    val listener = object : AnalyticsListener {
        override fun onEvents(p: Player, events: AnalyticsListener.Events) {
            if (events.containsAny(AnalyticsListener.EVENT_PLAYBACK_STATE_CHANGED, AnalyticsListener.EVENT_PLAY_WHEN_READY_CHANGED,
                    AnalyticsListener.EVENT_IS_LOADING_CHANGED, AnalyticsListener.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                    AnalyticsListener.EVENT_MEDIA_ITEM_TRANSITION, AnalyticsListener.EVENT_PLAYER_ERROR)) report(PlaybackDiagnosticEvent.STATE)
        }
        override fun onLoadStarted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, retryCount: Int) =
            report(PlaybackDiagnosticEvent.LOAD_START, info, retryCount)
        override fun onLoadCompleted(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
            report(PlaybackDiagnosticEvent.LOAD_END, info)
        override fun onLoadCanceled(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) =
            report(PlaybackDiagnosticEvent.LOAD_CANCEL, info)
        override fun onLoadError(time: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData,
            error: IOException, wasCanceled: Boolean) =
            report(PlaybackDiagnosticEvent.LOAD_ERROR, info, failure = playbackDiagnosticFailure(error))
    }
    val sample = object : Runnable {
        override fun run() {
            if (closed || !log.available) return
            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) report(PlaybackDiagnosticEvent.SAMPLE)
            if (log.available) handler.postDelayed(this, 5000)
        }
    }
    player.addAnalyticsListener(listener)
    report(PlaybackDiagnosticEvent.ATTACHED)
    handler.postDelayed(sample, 5000)
    return AutoCloseable {
        if (!closed) {
            closed = true
            handler.removeCallbacks(sample)
            player.removeAnalyticsListener(listener)
        }
    }
}
