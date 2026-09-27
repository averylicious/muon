package dev.avery.muon

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController

/** How wide the landscape player panel is (mockup B in `docs/design/landscape/`). */
internal val PLAYER_PANEL_WIDTH = 272.dp

/**
 * The mini player for a phone held sideways (mockup B): a panel as tall as the screen beside the
 * list, instead of a strip under it, so the list keeps the whole height.
 *
 * Tapping the panel opens Now Playing, as tapping the mini player does. The cover swipes to skip, as
 * Now Playing's does; the mini player's upward drag has no place in a panel that already reaches the
 * top, so opening is a tap here. Content scrolls rather than clipping if large text needs more height.
 */
@Composable
internal fun PlayerPanel(p: PlaybackUi, position: () -> Long, revision: () -> Int, player: MediaController?,
    open: () -> Unit, lyrics: () -> Unit, queue: () -> Unit, modifier: Modifier = Modifier) {
    val item = p.item ?: return
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceVariant, shape = RoundedCornerShape(24.dp),
        modifier = modifier.width(PLAYER_PANEL_WIDTH).fillMaxHeight()
            .clickable(enabled = player != null, onClickLabel = "Open Now Playing", onClick = open)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SwipeableArtwork(p, player, revision, Modifier.size(120.dp))
            Spacer(Modifier.height(4.dp))
            Text(item.mediaMetadata.title?.toString().orEmpty(), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.mediaMetadata.artist?.toString().orEmpty(), color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            PanelProgress(position, p.duration)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                Control("previous", "Previous track", p.previous && player != null) { player?.seekToPreviousMediaItem() }
                FilledIconButton(onClick = { if (p.playing) player?.pause() else player?.play() }, enabled = player != null,
                    shape = playButtonShape(p.playing, 56.dp),
                    modifier = Modifier.size(56.dp).semantics { contentDescription = if (p.playing) "Pause" else "Play" }) {
                    Crossfade(p.playing, animationSpec = motionShort(), label = "panel play/pause") { playing ->
                        MuonIcon(if (playing) "pause" else "play", Modifier.size(28.dp))
                    }
                }
                Control("next", "Next track", p.next && player != null) { player?.seekToNextMediaItem() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Control("lyrics", "Lyrics", action = lyrics)
                Control("queue", "Queue", action = queue)
            }
        }
    }
}

/** A thin bar with the times under it; only this reads the ticking position. */
@Composable
private fun PanelProgress(position: () -> Long, duration: Long) {
    val at = position()
    Column {
        LinearProgressIndicator(progress = { progressFraction(at, duration) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp), drawStopIndicator = {})
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(formatTime(at), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (duration > 0) Text(formatTime(duration), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
