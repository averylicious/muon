package dev.avery.muon

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
    // Rows join into one group per section, with motion as they come, go and move: a deleted copy folds
    // away while the rest close up, and Show brings the kept rows in.
    @Composable
    fun LazyItemScope.row(entry: SavedEntry, index: Int, size: Int) {
        SavedRow(entry, current == entry.ref.handle, playable, groupPlace(index, size),
            open = { details = entry }, play = { play(entry) },
            modifier = Modifier.animateItem(fadeInSpec = motionMedium(), placementSpec = motionSpatial(), fadeOutSpec = motionShort()))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = if (back != null) 4.dp else 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (back != null) IconButton(onClick = back, modifier = Modifier.semantics { contentDescription = "Back" }) {
                MuonIcon("back")
            }
            // With Expressive motion the title is large, under the bar, with a summary (the Refined mockup).
            if (Expressive.motion) Spacer(Modifier.weight(1f))
            else Text("On this device", style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).padding(start = if (back != null) 4.dp else 0.dp).semantics { heading() })
            IconButton(enabled = !busy, onClick = refresh, modifier = Modifier.semantics { contentDescription = "Refresh list" }) {
                MuonIcon("refresh")
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" })
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = 24.dp + LocalUnderBars.current)) {
            if (Expressive.motion) item(key = "title") {
                SavedTitle(savedSummary(count, DownloadMarks.bytes.takeIf { DownloadMarks.bytesKnown }, PlayedCacheState.used,
                    onCard = store?.card != null))
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
            item(key = "storage") { StorageCard { explain = true } }
            if (paged) item(key = "page-top") { SavedPageBar(offset, end, count, busy, page) }
            if (sections.downloads.isNotEmpty()) {
                item(key = "downloads") { SectionHeading("Downloads", sections.downloads.size) }
                itemsIndexed(sections.downloads, key = { _, it -> it.ref.handle }, contentType = { _, _ -> "saved" }) { i, it ->
                    row(it, i, sections.downloads.size)
                }
            }
            if (sections.listening.isNotEmpty()) {
                item(key = "listening") {
                    ListeningHeading(sections.listening.size, listeningShown,
                        toggle = if (listeningForced) null else ({ listeningChosen = !listeningChosen }))
                }
                if (listeningShown) itemsIndexed(sections.listening, key = { _, it -> it.ref.handle }, contentType = { _, _ -> "saved" }) { i, it ->
                    row(it, i, sections.listening.size)
                }
            }
            if (paged) item(key = "page-bottom") { SavedPageBar(offset, end, count, busy, page) }
            else item(key = "total") {
                Text("$count on this device", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
        }
        PreparingPill(preparing, cancelPlayback, Modifier.align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp + LocalUnderBars.current))
        }
    }
    if (explain) ExplainSheet { explain = false }
    details?.let { entry ->
        val source = storageFor(entry)
        val check = remember(source) { source?.let { owner -> { key: String -> owner.freshMigrationAvailable(key) } } }
        val updateAllowed = playable && DownloadMarks.moving == null && source != null
        val cardMissing = store?.missingPartitionCardVolume != null
        // Offered only once the fresh check for exactly this copy says yes; closing the panel cancels it.
        var updateConfirmed by remember(entry.ref.handle) { mutableStateOf(false) }
        LaunchedEffect(entry.ref.handle, entry.complete, updateAllowed, cardMissing, check) {
            updateConfirmed = false
            if (updateAllowed) updateConfirmed = storageUpdateConfirmed(entry, cardMissing, check)
        }
        SavedDetailsSheet(entry, current == entry.ref.handle, canPlay = playable && entry.complete, canDelete = deletable,
            dismiss = { details = null }, play = { play(entry) }, delete = { if (!busy && error == null) confirm = entry },
            update = if (updateConfirmed) ({ if (playable && DownloadMarks.moving == null) migration = entry }) else null)
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

/**
 * "Preparing playback…" with Cancel, floating above the bars instead of as a row at the top of the
 * list: as a row it pushed every copy down a line and back up on each tap (the user's recording on
 * the POCO, .1370). It waits [PREPARING_SHOWN_AFTER_MS] before showing, so a quick start doesn't
 * flash it, and rises in and sinks away on Muon's motion springs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreparingPill(preparing: Boolean, cancel: () -> Unit, modifier: Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(preparing) {
        if (!preparing) { shown = false; return@LaunchedEffect }
        delay(PREPARING_SHOWN_AFTER_MS)
        shown = true
    }
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(shown, modifier,
        enter = fadeIn(motionShort()) + slideInVertically(motionSpatial()) { it / 2 },
        exit = fadeOut(motionShort()) + slideOutVertically(motionSpatial()) { it / 2 }) {
        Surface(shape = RoundedCornerShape(50), color = colors.inverseSurface, contentColor = colors.inverseOnSurface,
            shadowElevation = 6.dp) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (Expressive.motion) LoadingIndicator(Modifier.size(28.dp), color = colors.inversePrimary)
                else CircularProgressIndicator(Modifier.size(20.dp), color = colors.inversePrimary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Preparing playback…", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = cancel, colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary)) { Text("Cancel") }
            }
        }
    }
}

/**
 * Text whose numbers change in place: the new value rolls up as the old one rolls away, as the Refined
 * mockup's counts do when a copy is deleted. Plain text with Expressive motion off.
 */
@Composable
private fun RollingText(text: String, style: TextStyle, color: Color) {
    if (!Expressive.motion) { Text(text, style = style, color = color); return }
    AnimatedContent(text, transitionSpec = {
        (slideInVertically(motionSpatial()) { it / 2 } + fadeIn(motionMedium()))
            .togetherWith(slideOutVertically(motionSpatial()) { -it / 2 } + fadeOut(motionShort()))
            .using(SizeTransform(clip = false))
    }, label = "rolling text") { shown -> Text(shown, style = style, color = color) }
}

/** The page's large title and the summary under it: how many songs, how much space, and where. */
@Composable
private fun SavedTitle(summary: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp)) {
        Text("On this device", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        RollingText(summary, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun savedSummary(count: Long, downloadBytes: Long?, cacheBytes: Long, onCard: Boolean): String {
    val songs = "$count ${if (count == 1L) "song" else "songs"}"
    val size = downloadBytes?.let { " · ${formatBytes(it + cacheBytes)}" } ?: ""
    return songs + size + if (onCard) " · phone and SD card" else " · this phone"
}

/** A song's size on a row: one decimal, as in "9.4 MB", since most songs are a few megabytes. */
internal fun songSize(bytes: Long): String = when {
    bytes >= 100_000_000 -> formatBytes(bytes)
    bytes >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1e6)
    else -> formatBytes(bytes)
}

/** How long preparing must last before the pill shows, as the seek bar waits before showing buffering. */
internal const val PREPARING_SHOWN_AFTER_MS = 300L

/**
 * One saved copy in its section's group: cover, title, artist and a status line, and a ⋮ at the far
 * right, centred however many lines the row has. The ⋮ and a long press open the same panel (the
 * details sheet with Play, Update storage format when confirmed, and Delete); a tap plays, or opens
 * the panel for a copy that can't play yet. The playing copy becomes its own rounded pill.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedRow(entry: SavedEntry, current: Boolean, ready: Boolean, place: GroupPlace,
    open: () -> Unit, play: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val title = entry.title()
    val artist = entry.artistLine()
    val status = entry.statusText(current)
    val playable = ready && entry.complete
    val shape = groupShape(place, current)
    val ground by animateColorAsState(if (current) colors.secondaryContainer else colors.surfaceContainerLow, motionMedium(), label = "row ground")
    val ink = if (current) colors.onSecondaryContainer else colors.onSurface
    val quiet = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant
    Surface(shape = shape, color = ground, contentColor = ink,
        modifier = modifier.fillMaxWidth().padding(start = GROUP_INSET, end = GROUP_INSET,
            top = if (place.startsGroup) 0.dp else GROUP_GAP)) {
        Row(Modifier.combinedClickable(onClickLabel = if (playable) "Play" else "Song options", onClick = if (playable) play else open,
                onLongClickLabel = "Song options", onLongClick = open)
            .heightIn(min = 72.dp).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SavedCover(entry, Modifier.size(52.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = quiet)
                status?.let { StatusLine(statusIcon(entry, current), it, strong = current || !entry.complete) }
            }
            if (Expressive.motion && entry.bytes > 0) Text(songSize(entry.bytes), style = MaterialTheme.typography.labelSmall,
                color = quiet, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 4.dp))
            IconButton(onClick = open, modifier = Modifier.size(48.dp).semantics { contentDescription = "More options for $title, $artist" }) {
                MuonIcon("more")
            }
        }
    }
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

/**
 * What's kept here, in one card: downloads stay until deleted; copies kept from listening fill the
 * played-song cache towards its limit, shown as a meter, and the oldest make room when it's full. Its
 * last line keeps "these play only from this phone" one tap away, as the info line did.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageCard(why: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val used = PlayedCacheState.used
    val limit = PlayedCacheState.limit
    val share by animateFloatAsState(if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f, motionSpatial(), label = "cache share")
    Surface(shape = RoundedCornerShape(26.dp), color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Counts and sizes in the card's titles where they are known (the Refined mockup).
            val downloads = DownloadMarks.done.takeIf { Expressive.motion && DownloadMarks.countsKnown }
            CardLine("download", downloads?.let { "Downloads · $it" } ?: "Downloads",
                if (Expressive.motion && DownloadMarks.bytesKnown && DownloadMarks.bytes > 0) "Stay until you delete them. ${formatBytes(DownloadMarks.bytes)}."
                else "Stay until you delete them.")
            CardLine("history", "Kept from listening",
                "${formatBytes(used)} of ${formatBytes(limit)}. When it's full, the oldest make room.") {
                // Still: the wave shows the fill, and nothing on this page moves on its own.
                if (Expressive.motion) LinearWavyProgressIndicator(progress = { share }, waveSpeed = 0.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                else LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Why", onClick = why)
                .heightIn(min = 40.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MuonIcon("info", Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("These play only from this phone. Why?", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
    }
}

@Composable
private fun CardLine(icon: String, title: String, body: String, extra: @Composable ColumnScope.() -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            MuonIcon(icon, Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            RollingText(title, MaterialTheme.typography.titleSmall, colors.onSurface)
            RollingText(body, MaterialTheme.typography.bodySmall, colors.onSurfaceVariant)
            extra()
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
    val turn by animateFloatAsState(if (shown) 180f else 0f, motionSpatial(), label = "chevron")
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Kept from listening · $size", style = MaterialTheme.typography.titleSmall, color = colors.primary,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (toggle != null) Surface(onClick = toggle, shape = RoundedCornerShape(50), color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer, modifier = Modifier.semantics { contentDescription = if (shown) "Hide" else "Show" }) {
            Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (shown) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
                Icon(painterResource(R.drawable.ic_collapse), contentDescription = null,
                    modifier = Modifier.padding(start = 2.dp).size(18.dp).rotate(turn))
            }
        }
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
    dismiss: () -> Unit, play: () -> Unit, delete: () -> Unit, update: (() -> Unit)?) {
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
            // Only after the fresh check for this exact copy confirmed it, as the row menu offered it before.
            AnimatedVisibility(update != null, enter = fadeIn(motionMedium()) + expandVertically(motionSpatial()),
                exit = fadeOut(motionShort()) + shrinkVertically(motionSpatial())) {
                FilledTonalButton(onClick = { update?.let { choose(it) } },
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp).fillMaxWidth()) {
                    MuonIcon("storage-update", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Update storage format")
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
