package dev.avery.muon

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class MediaVolumeState(
    val minimum: Int,
    val maximum: Int,
    val current: Int,
    val fixed: Boolean,
) {
    val percent: Int
        get() = if (maximum == minimum) 100
        else ((current - minimum) * 100f / (maximum - minimum)).toInt().coerceIn(0, 100)
}

internal fun normalizedVolumeState(minimum: Int, maximum: Int, current: Int, fixed: Boolean): MediaVolumeState {
    val upper = maximum.coerceAtLeast(minimum)
    return MediaVolumeState(minimum, upper, current.coerceIn(minimum, upper), fixed)
}

internal class MediaVolumeController(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    var state by mutableStateOf(readState())
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val poll = object : Runnable {
        override fun run() {
            if (running) {
                refresh()
                handler.postDelayed(this, 500)
            }
        }
    }

    fun start() {
        running = true
        refresh()
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 500)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(poll)
    }

    fun setVolume(volume: Int) {
        val latest = readState()
        if (latest.fixed) {
            state = latest
            error = null
            return
        }
        try {
            audio.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                volume.coerceIn(latest.minimum, latest.maximum),
                0,
            )
            error = null
        } catch (_: SecurityException) {
            error = "Android blocked this change. Use the device volume controls instead."
        } finally {
            refresh()
        }
    }

    private fun refresh() {
        state = readState()
    }

    private fun readState() = normalizedVolumeState(
        minimum = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC),
        maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        current = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        fixed = audio.isVolumeFixed,
    )
}

@Composable
internal fun rememberMediaVolumeController(): MediaVolumeController {
    val applicationContext = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember(applicationContext) { MediaVolumeController(applicationContext) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.start()
        onDispose {
            lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    return controller
}
