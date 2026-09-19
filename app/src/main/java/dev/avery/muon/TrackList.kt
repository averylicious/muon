package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun TrackList(tracks: List<TauonTrack>, endpoint: ServerEndpoint?, currentId: String?, ready: Boolean,
    emptyText: String, loading: Boolean = false, play: (TauonTrack) -> Unit) {
    if (tracks.isEmpty() && loading) PlaceholderRows()
    else if (tracks.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val keys = remember(tracks) { trackKeys(tracks) }
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(tracks, key = { i, _ -> keys[i] }, contentType = { _, _ -> "track" }) { _, t ->
                TrackRow(t, endpoint, currentId == "${endpoint?.origin}/${t.id}", ready,
                    Modifier.animateItem(placementSpec = motionMedium())) { play(t) }
            }
        }
    }
}

@Composable
private fun TrackRow(t: TauonTrack, endpoint: ServerEndpoint?, current: Boolean, ready: Boolean,
    modifier: Modifier = Modifier, play: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
        .clickable(enabled = t.playable && ready, onClick = play)
        .padding(horizontal = 24.dp, vertical = 8.dp)
        .then(if (current) Modifier.semantics { stateDescription = "Now playing" } else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        // The current track is marked by something appearing, not only by a change of hue. The
        // marker reserves its width either way so every row starts on the same line.
        Box(Modifier.width(3.dp).height(32.dp)
            .then(if (current) Modifier.background(MaterialTheme.colorScheme.primary,
                RoundedCornerShape(2.dp)) else Modifier))
        Spacer(Modifier.width(9.dp))
        Artwork(endpoint?.url("/api1/pic/small/${t.id}"), Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
            Text(if (t.playable) "${t.artist} · ${t.album}" else "Unavailable for direct streaming · ${t.artist}",
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall)
        }
        // A minimum width keeps the durations on one right edge; a long duration or a large font
        // scale grows the column instead of clipping, taking the space from the title beside it.
        Text(formatTime(t.durationMs), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
            maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
    }
}

/**
 * Shown while the first library load runs. Without it the screen reads as an empty library until
 * every playlist has been fetched, which is the wrong message while it is still working.
 */
@Composable
private fun PlaceholderRows() {
    val colour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Column(Modifier.fillMaxWidth()) {
        repeat(8) { index ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(colour))
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    // Uneven widths so it reads as a list of titles rather than as a broken grid.
                    Box(Modifier.fillMaxWidth(if (index % 3 == 0) 0.7f else 0.5f).height(12.dp)
                        .clip(RoundedCornerShape(6.dp)).background(colour))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth(if (index % 2 == 0) 0.35f else 0.45f).height(10.dp)
                        .clip(RoundedCornerShape(5.dp)).background(colour))
                }
            }
        }
    }
}

internal fun formatTime(ms: Long): String = "${ms / 60000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
