package dev.avery.muon

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController

@Composable
internal fun NowPlaying(p: PlaybackUi, position: () -> Long, player: MediaController?, lyrics: () -> Unit) {
    if (p.item == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Choose a track from your library to start listening.") }
        return
    }
    var showVolume by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val narrow = maxWidth < 360.dp
        // Keep the compact portrait design, but allow every control to remain reachable in
        // landscape, split screen, large text, or when an error needs additional space.
        val scrollable = maxHeight < 600.dp * fontScale || narrow || p.error != null
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize()
            .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PLAYING ON THIS DEVICE", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
            Box((if (scrollable) Modifier.height(160.dp) else Modifier.weight(1f)).fillMaxWidth(),
                contentAlignment = Alignment.Center) {
                Artwork(p.item.mediaMetadata.artworkUri?.toString(),
                    Modifier.widthIn(max = 400.dp).aspectRatio(1f).clip(RoundedCornerShape(24.dp)))
            }
            Column(Modifier.fillMaxWidth()) {
                Text(p.item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.item.mediaMetadata.artist?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.item.mediaMetadata.albumTitle?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p.error != null) ErrorCard(p.error, "Retry stream") { player?.prepare(); player?.play() }
            if (p.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            SeekControls(p.item.mediaId, position, p.duration, p.seekable, player)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                if (!narrow) ShuffleControl(p, player)
                Control("previous", "Previous track", p.previous && player != null) { player?.seekToPreviousMediaItem() }
                FilledIconButton(onClick = { if (p.playing) player?.pause() else player?.play() }, enabled = player != null,
                    modifier = Modifier.size(72.dp).semantics { contentDescription = if (p.playing) "Pause" else "Play" }) {
                    Crossfade(p.playing, animationSpec = motionShort(), label = "play/pause") { playing ->
                        MuonIcon(if (playing) "pause" else "play", Modifier.size(32.dp))
                    }
                }
                Control("next", "Next track", p.next && player != null) { player?.seekToNextMediaItem() }
                if (!narrow) RepeatControl(p, player)
            }
            if (narrow) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ShuffleControl(p, player)
                RepeatControl(p, player)
            }
            // Wrapping preserves readable labels at large font/display sizes.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = lyrics) { Text("Lyrics") }
                TextButton(onClick = { showVolume = true }) {
                    MuonIcon("volume", Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Volume")
                }
            }
        }
    }
    if (showVolume) MediaVolumeDialog { showVolume = false }
}

@Composable
private fun ShuffleControl(p: PlaybackUi, player: MediaController?) {
    ToggleControl("shuffle", "Shuffle", if (p.shuffle) "On" else "Off", p.shuffle, player != null) {
        player?.shuffleModeEnabled = !p.shuffle
    }
}

@Composable
private fun RepeatControl(p: PlaybackUi, player: MediaController?) {
    ToggleControl(repeatModeIcon(p.repeatMode), "Repeat", repeatModeName(p.repeatMode),
        p.repeatMode != Player.REPEAT_MODE_OFF, player != null) {
        player?.repeatMode = nextRepeatMode(p.repeatMode)
    }
}

@Composable
private fun SeekControls(mediaId: String, position: () -> Long, duration: Long, seekable: Boolean, player: MediaController?) {
    var scrub by remember(mediaId) { mutableStateOf<Float?>(null) }
    val range = duration.coerceAtLeast(1).toFloat()
    val elapsed = scrub ?: position().toFloat().coerceIn(0f, range)
    Column {
        Slider(value = elapsed, onValueChange = { scrub = it }, valueRange = 0f..range,
            onValueChangeFinished = { scrub?.let { player?.seekTo(it.toLong()) }; scrub = null },
            enabled = seekable && player != null, modifier = Modifier.semantics { contentDescription = "Seek position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(elapsed.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MediaVolumeDialog(dismiss: () -> Unit) {
    val volume = rememberMediaVolumeController()
    val state = volume.state
    AlertDialog(
        onDismissRequest = dismiss,
        confirmButton = { TextButton(onClick = dismiss) { Text("Done") } },
        icon = { MuonIcon("volume") },
        title = { Text("Media volume") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.fixed) Text(
                    text = "Volume is fixed by this device.",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                MediaVolumeSlider(state, volume::setVolume)
                volume.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}
