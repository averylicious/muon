package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    canSave: Boolean = false, save: () -> Unit = {}, play: (() -> Unit)? = null) {
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
        // The Canary experiment, as the Refined design has it (issue #661): Play, Play next and Add to
        // queue lead the panel as one connected group, and the rest follow as one joined group of rows.
        val grouped = canQueue && Expressive.motion
        if (grouped) PlayGroup(play?.let { p -> { choose(p) } }, { choose { queue(true) } }, { choose { queue(false) } })
        // Keeps a new copy on the phone (#112). A live song never removes or claims a copy saved
        // earlier under its track number (#213): those are managed in Saved copies.
        val rows = buildList<Triple<String, String, Pair<String?, () -> Unit>>> {
            if (canQueue && !grouped) {
                add(Triple("play-next", "Play next", null to { choose { queue(true) } }))
                add(Triple("add-queue", "Add to queue", null to { choose { queue(false) } }))
            }
            album?.let { a -> add(Triple("album", "Go to album", a.title.ifBlank { null } to { choose { goToAlbum(a) } })) }
            artists.forEach { a -> add(Triple("artist", "Go to artist", artistLabel(a.name) to { choose { goToArtist(a) } })) }
            if (canSave) add(Triple("download", "Save a copy", null to { choose(save) }))
        }
        if (Expressive.motion) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(GROUP_GAP)) {
            rows.forEachIndexed { i, (icon, label, rest) ->
                GroupedSheetAction(icon, label, rest.first, groupPlace(i, rows.size), rest.second)
            }
        } else {
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Column(Modifier.padding(vertical = 8.dp)) {
                // Without the group, an album or a single artist needs no second line.
                rows.forEach { (icon, label, rest) ->
                    SheetAction(icon, label, rest.first.takeIf { icon == "artist" && artists.size > 1 }, rest.second)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Play, Play next and Add to queue as one connected group: round outer ends, tight inner corners, each
 * fully round while pressed, as Material 3 Expressive's connected button group draws them. Play is
 * filled, the queue actions tonal. Each label sits under its icon, so all three fit a phone's width.
 * Without [play] (no list to play from), the queue pair alone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayGroup(play: (() -> Unit)?, playNext: () -> Unit, addToQueue: () -> Unit) {
    val outer = 24.dp
    val inner = 8.dp
    val pressed = RoundedCornerShape(50)
    val gap = ButtonGroupDefaults.ConnectedSpaceBetween
    val count = if (play != null) 3 else 2
    fun shape(i: Int) = RoundedCornerShape(if (i == 0) outer else inner, if (i == count - 1) outer else inner,
        if (i == count - 1) outer else inner, if (i == 0) outer else inner)
    @Composable
    fun Label(icon: String, text: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MuonIcon(icon, Modifier.size(20.dp))
            Spacer(Modifier.height(6.dp))
            Text(text, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
    val padding = PaddingValues(horizontal = 6.dp, vertical = 12.dp)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        var i = 0
        if (play != null) {
            val at = i++
            val playPress = remember { MutableInteractionSource() }
            Button(onClick = play, interactionSource = playPress, modifier = pressedWeight(playPress, 1f).heightIn(min = 72.dp), contentPadding = padding,
                shapes = ButtonDefaults.shapes(shape(at), pressed)) { Label("play", "Play") }
        }
        val next = i++
        val nextPress = remember { MutableInteractionSource() }
        FilledTonalButton(onClick = playNext, interactionSource = nextPress, modifier = pressedWeight(nextPress, 1f).heightIn(min = 72.dp), contentPadding = padding,
            shapes = ButtonDefaults.shapes(shape(next), pressed)) { Label("play-next", "Play next") }
        val add = i
        val addPress = remember { MutableInteractionSource() }
        FilledTonalButton(onClick = addToQueue, interactionSource = addPress, modifier = pressedWeight(addPress, 1f).heightIn(min = 72.dp), contentPadding = padding,
            shapes = ButtonDefaults.shapes(shape(add), pressed)) { Label("add-queue", "Add to queue") }
    }
}

/** One of the panel's rows in a joined group, with a second line naming where it goes. */
@Composable
private fun GroupedSheetAction(icon: String, label: String, detail: String?, place: GroupPlace, onClick: () -> Unit) {
    SegmentedListItem(onClick = onClick, shapes = ListItemDefaults.shapes(shape = groupShape(place, false)),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = { MuonIcon(icon) },
        supportingContent = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        modifier = Modifier.fillMaxWidth()) { Text(label) }
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
