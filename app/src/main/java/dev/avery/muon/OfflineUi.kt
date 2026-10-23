package dev.avery.muon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * A saved copy's state beside it (mockup 02): a filled check once it is kept, a spinning arc while it
 * downloads, a dotted ring while it waits. Shown only on saved copies, never on a live song (#213):
 * a copy saved under a track number does not tell what that number plays now.
 */
@Composable
internal fun DownloadBadge(mark: DownloadMark?) {
    if (mark == null) return
    val colors = MaterialTheme.colorScheme
    Box(Modifier.padding(start = 8.dp).size(20.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        when (mark) {
            DownloadMark.Done -> Box(Modifier.size(20.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalContentColor provides colors.onPrimary) { MuonIcon("check", Modifier.size(14.dp)) }
            }
            DownloadMark.Downloading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            DownloadMark.Queued -> Canvas(Modifier.size(16.dp)) {
                drawCircle(colors.outline, style = Stroke(1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.5.dp.toPx()))))
            }
        }
    }
}

/**
 * Save copies, for an album's, an artist's or a playlist's songs (mockup 02): a tonal button with how
 * many songs and roughly how much space. Each press saves new copies (#213): whether a copy kept
 * earlier under the same track numbers is still these songs is not known, so this never says they are
 * already saved and never removes anything. Saved copies, their progress and Remove are in Saved copies.
 */
@Composable
internal fun DownloadAll(tracks: List<TauonTrack>, endpoint: ServerEndpoint?) {
    if (endpoint == null) return
    val playable = tracks.filter { it.playable }
    if (playable.isEmpty()) return
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    // Directly under Play and Shuffle, with the same gap below as above it. The details wrap under the
    // button when large text leaves no room beside it (#16 QA).
    FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { OfflineStore.add(context, endpoint, playable) }, modifier = Modifier.heightIn(min = 48.dp)) {
            MuonIcon("download", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save copies")
        }
        Text("${playable.size} ${if (playable.size == 1) "song" else "songs"}\nabout ${formatBytes(downloadEstimate(playable))}",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}
