package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download

/**
 * Saved copies (#213): every copy kept on the phone or its SD card, each its own row however many share
 * a song's details, with Play and Remove. All are marked Unverified, in words as well as colour: Muon
 * cannot tell whether a copy is still the song Tauon has under the number it was saved from, so the
 * live library never uses them and they play only from here, with no network.
 */
@Composable
internal fun SavedCopies(entries: List<SavedEntry>, current: String?, ready: Boolean, cardUnavailable: Boolean,
    play: (List<SavedEntry>, SavedEntry) -> Unit, remove: (SavedEntry) -> Unit, back: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf<SavedEntry?>(null) }
    val playable = remember(entries) { entries.filter { it.complete } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (back != null) 4.dp else 20.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = "Back" }) {
                MuonIcon("back")
            }
            Text("Saved copies", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Copies kept on this phone, as they were saved. Muon can't confirm they still match what Tauon " +
            "has, so each is marked $UNVERIFIED and plays only from here.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (cardUnavailable) Text("The SD card isn't available, so its copies aren't shown or changed.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (entries.isEmpty()) {
            Text("No saved copies yet. Long-press a song and choose Save a copy.",
                style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(20.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(entries, key = { it.ref.handle }, contentType = { "saved" }) { entry ->
                SavedRow(entry, current == entry.ref.handle, ready,
                    play = { play(playable, entry) }, remove = { confirm = entry })
            }
        }
    }
    confirm?.let { entry ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Remove this saved copy?") },
            text = { Text("“${entry.title()}” is removed from this phone. Other saved copies, and the song in Tauon, aren't changed.") },
            confirmButton = { TextButton(onClick = { confirm = null; remove(entry) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SavedRow(entry: SavedEntry, current: Boolean, ready: Boolean, play: () -> Unit, remove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = when {
        entry.stoppedAfterRestart -> if (entry.complete) "Kept after restart · plays from saved bytes"
            else "Kept after restart · incomplete · save a new copy from the live song"
        entry.state == Download.STATE_QUEUED || entry.state == Download.STATE_RESTARTING -> "Waiting to save"
        entry.state == Download.STATE_DOWNLOADING -> "Saving…"
        entry.state == Download.STATE_FAILED -> "Saving failed · can't play"
        !entry.complete -> "Incomplete · can't play"
        else -> null
    }
    ListItem(
        headlineContent = {
            Text(entry.title(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
        },
        supportingContent = {
            Column {
                Text(entry.subtitle(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                status?.let { Text(it, color = if (entry.complete) colors.onSurfaceVariant else colors.error) }
                if (!entry.removable) Text("Kept: Muon can't tell its bytes belong to it alone", color = colors.onSurfaceVariant)
            }
        },
        leadingContent = {
            Artwork(if (entry.ownCover) savedArtUrl(entry.ref.requestId) else null,
                Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
        },
        trailingContent = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (entry.ref.source == SavedSource.Download) DownloadBadge(DownloadMarks.marks[entry.ref.requestId])
                if (entry.removable) IconButton(onClick = remove,
                    modifier = Modifier.semantics { contentDescription = "Remove saved copy ${entry.title()}" }) {
                    MuonIcon("delete")
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (current) colors.secondaryContainer else colors.surface),
        modifier = Modifier.clickable(enabled = ready && entry.complete, onClickLabel = "Play saved copy", onClick = play),
    )
}
