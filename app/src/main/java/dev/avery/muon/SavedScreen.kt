package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * On this device (saved copies, #213): every copy kept on the phone or its SD card, each its own row
 * however many share a song's details. Downloads and copies kept from listening are shown apart, on
 * the page shown only (see [savedPageSections]); the catalog's own order and paging are unchanged,
 * so playing a row still queues in that order. Every copy plays only from here, with no network:
 * Muon can't tell whether a copy is still the song Tauon has under the number it was saved from. That
 * is explained once, behind a compact line, and never decides whether Delete is offered.
 */
@Composable
internal fun SavedCopies(entries: List<SavedEntry>, current: String?, ready: Boolean, cardUnavailable: Boolean,
    play: (SavedEntry) -> Unit, remove: (SavedEntry) -> Unit, count: Long, offset: Long, busy: Boolean,
    error: String?, page: (Long) -> Unit, refresh: () -> Unit, preparing: Boolean, cancelPlayback: () -> Unit, back: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberLazyListState()
    LaunchedEffect(offset) { scroll.scrollToItem(0) }
    var confirm by remember { mutableStateOf<SavedEntry?>(null) }
    var migration by remember { mutableStateOf<SavedEntry?>(null) }
    var details by remember { mutableStateOf<SavedEntry?>(null) }
    var explain by remember { mutableStateOf(false) }
    var listeningChosen by rememberSaveable { mutableStateOf(false) }
    val store = OfflineStore.current()
    val playable = ready && !busy && !preparing && error == null
    // The same guard Remove always had: a list being loaded or in error offers no Delete for now.
    val deletable = !busy && error == null
    val sections = remember(entries) { savedPageSections(entries) }
    val paged = savedPaged(count, offset)
    val listeningForced = savedListeningShown(false, sections, current)
    val listeningShown = savedListeningShown(listeningChosen, sections, current)
    val end = minOf(count, offset + SAVED_PAGE_SIZE)
    fun storageFor(entry: SavedEntry) = if (entry.ref.shelf == SavedShelf.Card) store?.card?.partition else store?.phone?.partition
    @Composable
    fun row(entry: SavedEntry) {
        val source = storageFor(entry)
        val check = remember(source) { source?.let { owner -> { key: String -> owner.freshMigrationAvailable(key) } } }
        SavedRow(entry, current == entry.ref.handle, playable, deletable,
            updateAllowed = playable && DownloadMarks.moving == null && source != null,
            cardMissing = store?.missingPartitionCardVolume != null,
            storageCheck = check,
            play = { play(entry) }, details = { details = entry },
            delete = { if (!busy && error == null) confirm = entry },
            update = { if (playable && DownloadMarks.moving == null) migration = entry })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (back != null) 4.dp else 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = "Back" }) {
                MuonIcon("back")
            }
            Text("On this device", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(start = if (back != null) 4.dp else 0.dp).semantics { heading() })
            IconButton(enabled = !busy, onClick = refresh, modifier = Modifier.semantics { contentDescription = "Refresh list" }) {
                MuonIcon("refresh")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" })
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = 24.dp + LocalUnderBars.current)) {
            if (preparing) item(key = "preparing") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Preparing playback…", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = cancelPlayback) { Text("Cancel") }
                }
            }
            if (cardUnavailable) item(key = "card") {
                Notice("sd-card", "SD card not found", "Copies on it are hidden and stay on the card.")
            }
            if (error != null) item(key = "error") {
                Text(error, color = colors.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            if (entries.isEmpty()) {
                item(key = "empty") { SavedEmpty(busy, error != null) }
                return@LazyColumn
            }
            item(key = "info") { InfoLine { explain = true } }
            if (paged) item(key = "page-top") { SavedPageBar(offset, end, count, busy, page) }
            if (sections.downloads.isNotEmpty()) {
                item(key = "downloads") { SectionHeading("Downloads", sections.downloads.size) }
                items(sections.downloads, key = { it.ref.handle }, contentType = { "saved" }) { row(it) }
            }
            if (sections.listening.isNotEmpty()) {
                item(key = "listening") {
                    ListeningHeading(sections.listening.size, listeningShown,
                        toggle = if (listeningForced) null else ({ listeningChosen = !listeningChosen }))
                }
                if (listeningShown) items(sections.listening, key = { it.ref.handle }, contentType = { "saved" }) { row(it) }
            }
            if (paged) item(key = "page-bottom") { SavedPageBar(offset, end, count, busy, page) }
            else item(key = "total") {
                Text("$count on this device", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
    }
    if (explain) ExplainSheet { explain = false }
    details?.let { entry ->
        SavedDetailsSheet(entry, current == entry.ref.handle, canPlay = playable && entry.complete, canDelete = deletable,
            dismiss = { details = null }, play = { play(entry) }, delete = { if (!busy && error == null) confirm = entry })
    }
    migration?.let { entry -> SavedMigrationDialog(entry) { migration = null } }
    confirm?.let { entry ->
        val played = entry.ref.source == SavedSource.Played
        val where = if (entry.ref.shelf == SavedShelf.Card) "SD card" else "phone"
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (played) "Delete from this device?" else "Delete this download?") },
            text = {
                Text("“${entry.title()}” will be deleted from this $where. Other copies here and your Tauon library aren't changed.")
            },
            confirmButton = { TextButton(onClick = { confirm = null; remove(entry) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SavedRow(entry: SavedEntry, current: Boolean, ready: Boolean, deletable: Boolean, updateAllowed: Boolean,
    cardMissing: Boolean, storageCheck: ((String) -> Boolean)?, play: () -> Unit, details: () -> Unit,
    delete: () -> Unit, update: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var menu by remember(entry.ref.handle) { mutableStateOf(false) }
    // Offered only once the fresh check for exactly this copy says yes; closing the menu cancels it.
    var updateConfirmed by remember(entry.ref.handle) { mutableStateOf(false) }
    LaunchedEffect(menu, entry.ref.handle, entry.complete, updateAllowed, cardMissing, storageCheck) {
        updateConfirmed = false
        if (menu && updateAllowed) updateConfirmed = storageUpdateConfirmed(entry, cardMissing, storageCheck)
    }
    val title = entry.title()
    val artist = entry.artistLine()
    val status = entry.statusText(current)
    ListItem(
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
        },
        supportingContent = {
            Column {
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                status?.let { StatusLine(statusIcon(entry, current), it, strong = current || !entry.complete) }
            }
        },
        leadingContent = { SavedCover(entry, Modifier.size(52.dp)) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "More options for $title, $artist" }) {
                    MuonIcon("more")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play") }, leadingIcon = { MuonIcon("play") },
                        enabled = ready && entry.complete, onClick = { menu = false; play() })
                    DropdownMenuItem(text = { Text("Details") }, leadingIcon = { MuonIcon("info") },
                        onClick = { menu = false; details() })
                    if (updateConfirmed) DropdownMenuItem(text = { Text("Update storage format") },
                        leadingIcon = { MuonIcon("storage-update") }, onClick = { menu = false; update() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(if (entry.ref.source == SavedSource.Played) "Delete from device" else "Delete download") },
                        leadingIcon = { MuonIcon("delete") }, enabled = deletable, onClick = { menu = false; delete() })
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (current) colors.secondaryContainer else colors.surface),
        modifier = Modifier.clickable(enabled = ready && entry.complete, onClickLabel = "Play", onClick = play),
    )
}

private fun statusIcon(entry: SavedEntry, current: Boolean): String = when {
    !entry.complete || entry.stoppedAfterRestart -> if (entry.state != null && entry.complete) "info" else "download"
    current -> "play"
    entry.ref.shelf == SavedShelf.Card -> "sd-card"
    else -> "info"
}

/** A status in words with an icon beside it, never colour alone. */
@Composable
private fun StatusLine(icon: String, text: String, strong: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        MuonIcon(icon, Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (strong) FontWeight.Medium else FontWeight.Normal,
            color = if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A saved copy's own cover, or, for one kept without a cover, the cookie placeholder used everywhere. */
@Composable
private fun SavedCover(entry: SavedEntry, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    if (entry.ownCover) Artwork(savedArtUrl(entry.ref.requestId), modifier.clip(shape))
    else CoverPlaceholder(modifier.clip(shape))
}

@Composable
private fun InfoLine(open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer,
        contentColor = colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MuonIcon("info", Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Plays from this device", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("Details", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            Icon(painterResource(R.drawable.ic_collapse), contentDescription = null, tint = colors.primary,
                modifier = Modifier.padding(start = 2.dp).size(18.dp)
                    .rotate(if (LocalLayoutDirection.current == LayoutDirection.Rtl) 90f else -90f))
        }
    }
}

@Composable
private fun Notice(icon: String, title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.padding(14.dp)) {
            MuonIcon(icon, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionHeading(label: String, size: Int) {
    Text("$label · $size", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
}

/** Kept from listening, collapsed until asked for; [toggle] is null while it must stay open. */
@Composable
private fun ListeningHeading(size: Int, shown: Boolean, toggle: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()
        .then(if (toggle != null) Modifier.clickable(onClickLabel = if (shown) "Hide" else "Show", onClick = toggle) else Modifier)
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)) {
        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Kept from listening · $size", style = MaterialTheme.typography.titleSmall, color = colors.primary,
                modifier = Modifier.weight(1f).semantics { heading() })
            if (toggle != null) {
                Text(if (shown) "Hide" else "Show", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Icon(painterResource(R.drawable.ic_collapse), contentDescription = null, tint = colors.primary,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(if (shown) 180f else 0f))
            }
        }
        Text("Saved automatically while you listened. When the listening cache is full, older ones make room.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** Only shown when there is more than one page: the sections above describe this page alone. */
@Composable
private fun SavedPageBar(offset: Long, end: Long, count: Long, busy: Boolean, page: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(enabled = !busy && offset > 0, onClick = { page(maxOf(0, offset - SAVED_PAGE_SIZE)) }) { Text("Previous") }
        Text("On this page: ${offset + 1}–$end of $count", style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        TextButton(enabled = !busy && offset + SAVED_PAGE_SIZE < count, onClick = { page(offset + SAVED_PAGE_SIZE) }) { Text("Next") }
    }
}

@Composable
private fun SavedEmpty(busy: Boolean, failed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (busy) { Text("Loading…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant); return@Column }
        if (failed) { Text("This list couldn't be loaded.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant); return@Column }
        CoverPlaceholder(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)))
        Spacer(Modifier.height(20.dp))
        Text("Nothing on this device yet", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Long-press a song and choose Save a copy to keep it here.", style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExplainSheet(dismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("Why these play from here", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            Text("Tauon can give a song's number to a different song after its library changes. Muon can't confirm that a copy here still matches what Tauon has now.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text("So these play only from this device, and your Tauon library never uses them in its place. You can still play or delete any of them.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = { scope.launch { sheet.hide(); dismiss() } }) { Text("Got it") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedDetailsSheet(entry: SavedEntry, current: Boolean, canPlay: Boolean, canDelete: Boolean,
    dismiss: () -> Unit, play: () -> Unit, delete: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    fun choose(action: () -> Unit) { scope.launch { sheet.hide(); dismiss(); action() } }
    val played = entry.ref.source == SavedSource.Played
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SavedCover(entry, Modifier.size(64.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(entry.title(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(entry.artistLine(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Fact(if (played) "history" else "download", "Kept as", if (played) "Kept from listening" else "Download",
                    if (played) "Saved automatically. It can make room for newer songs." else "You chose to keep this.")
                Fact(if (entry.ref.shelf == SavedShelf.Card) "sd-card" else "download", "Stored on",
                    (if (entry.ref.shelf == SavedShelf.Card) "SD card" else "Phone") + " · " + formatBytes(entry.bytes), null)
                Fact("info", "Plays from", "This device only", "Muon can't confirm it still matches the song in Tauon.")
                entry.statusText(current)?.let { Fact(statusIcon(entry, current), "Status", it, null) }
            }
            Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = canPlay, onClick = { choose(play) }) {
                    MuonIcon("play", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Play")
                }
                OutlinedButton(enabled = canDelete, onClick = { choose(delete) }) {
                    MuonIcon("delete", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: String, label: String, value: String, supporting: String?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Row(Modifier.padding(top = 2.dp)) {
            MuonIcon(icon, Modifier.padding(top = 2.dp).size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(value, style = MaterialTheme.typography.bodyLarge)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
    }
}
