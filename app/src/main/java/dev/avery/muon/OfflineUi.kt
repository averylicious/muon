package dev.avery.muon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A song's download state beside its length (mockup 02): a filled check once it is on the phone, a
 * spinning arc while it downloads, a dotted ring while it waits. Nothing for a song not downloaded.
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

/** The download state of one song as the UI reads it, keyed as the player keys songs. */
@Composable
internal fun downloadMark(endpoint: ServerEndpoint?, track: TauonTrack): DownloadMark? =
    endpoint?.let { DownloadMarks.marks[downloadId(it.origin, track.id)] }

/**
 * Download all, for an album's or an artist's songs (mockup 02): a tonal button with how many songs
 * and roughly how much space; while they download, a card with progress and Cancel; once all are on
 * the phone, a line saying so with Remove.
 */
@Composable
internal fun DownloadAll(tracks: List<TauonTrack>, endpoint: ServerEndpoint?) {
    if (endpoint == null) return
    val playable = tracks.filter { it.playable }
    if (playable.isEmpty()) return
    val context = LocalContext.current
    val ids = playable.map { downloadId(endpoint.origin, it.id) }
    val progress = downloadProgress(ids, DownloadMarks.marks)
    val colors = MaterialTheme.colorScheme
    // Directly under Play and Shuffle, with the same gap below as above it.
    Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        when {
            progress.pending > 0 -> Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading ${progress.done} of ${progress.wanted}", fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f))
                        // Stops what has not finished; songs already on the phone stay.
                        TextButton(onClick = {
                            OfflineStore.remove(context, ids.filter { DownloadMarks.marks[it] != DownloadMark.Done })
                        }) { Text("Cancel") }
                    }
                    LinearProgressIndicator(progress = { progress.done.toFloat() / progress.wanted },
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
                }
            }
            progress.all -> Row(verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides colors.primary) { MuonIcon("check", Modifier.size(18.dp)) }
                Text("Downloaded · ${progress.wanted} ${if (progress.wanted == 1) "song" else "songs"}",
                    color = colors.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { OfflineStore.remove(context, ids) }) { Text("Remove") }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                val missing = playable.filterIndexed { i, _ -> DownloadMarks.marks[ids[i]] != DownloadMark.Done }
                FilledTonalButton(onClick = { OfflineStore.add(context, endpoint, missing) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    MuonIcon("download", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Download all")
                }
                Text("${missing.size} ${if (missing.size == 1) "song" else "songs"}\nabout ${formatBytes(downloadEstimate(missing))}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}
