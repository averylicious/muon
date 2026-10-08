package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The album a song belongs to, for *Go to album*; none for a song without an album tag. */
internal fun songAlbum(track: TauonTrack, albums: List<LibraryAlbum>?): LibraryAlbum? {
    if (track.album.isBlank()) return null
    val key = trackAlbumKey(track)
    return albums?.firstOrNull { it.key == key }
}

/** Each artist a song is credited to, in credit order, for *Go to artist*; none for an untagged song. */
internal fun songArtists(track: TauonTrack, artists: List<LibraryArtist>?): List<LibraryArtist> {
    if (artists == null || track.artist.isBlank()) return emptyList()
    val byKey = trackArtistKeys(track)
    return byKey.mapNotNull { key -> artists.firstOrNull { it.key == key } }
}

/** The snackbar after a queue action, in the action's own words, naming the song. */
internal fun queuedMessage(title: String, next: Boolean): String {
    val name = "“${title.ifBlank { "Untitled" }}”"
    return if (next) "$name will play next" else "$name added to the queue"
}

/**
 * What can be done with one song (#46, mockup 14), opened by a long press on it: its cover, title,
 * artist and album repeated at the top, because the sheet covers the row; then Play next, Add to
 * queue, Go to album and Go to artist. [album] and [artists] leave out the page already on show, and
 * a song credited to several artists offers each. The sheet closes before the chosen action runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongActionsSheet(track: TauonTrack, endpoint: ServerEndpoint?, canQueue: Boolean,
    album: LibraryAlbum?, artists: List<LibraryArtist>, dismiss: () -> Unit,
    queue: (next: Boolean) -> Unit, goToAlbum: (LibraryAlbum) -> Unit, goToArtist: (LibraryArtist) -> Unit,
    canSave: Boolean = false, save: () -> Unit = {}) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var choosing by remember { mutableStateOf(false) }
    fun choose(action: () -> Unit) {
        if (choosing) return
        choosing = true
        scope.launch {
            try { completeSheetAction({ sheet.hide() }, { !sheet.isVisible }, dismiss, action) }
            finally { choosing = false }
        }
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(endpoint?.url("/api1/pic/medium/${track.id}"), Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(track.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                displayCredits(track.artist).ifBlank { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                track.album.trim().ifBlank { null }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp))
        Column(Modifier.padding(vertical = 8.dp)) {
            if (canQueue) {
                SheetAction("play-next", "Play next") { choose { queue(true) } }
                SheetAction("add-queue", "Add to queue") { choose { queue(false) } }
            }
            // Keeps a new copy on the phone (#112). A live song never removes or claims a copy saved
            // earlier under its track number (#213): those are managed in Saved copies.
            if (canSave) SheetAction("download", "Save a copy") { choose(save) }
            album?.let { a -> SheetAction("album", "Go to album") { choose { goToAlbum(a) } } }
            artists.forEach { a ->
                SheetAction("artist", "Go to artist", if (artists.size > 1) artistLabel(a.name) else null) { choose { goToArtist(a) } }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SheetAction(icon: String, label: String, detail: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { MuonIcon(icon) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    )
}
