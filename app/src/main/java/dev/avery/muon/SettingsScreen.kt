package dev.avery.muon

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Rows in a group share a shape family: the group's outer corners are large, the seams are small. */
private val GroupOuterCorner = 20.dp
private val GroupInnerCorner = 4.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(model: LibraryModel, appearance: AppearanceSettings, disconnect: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // Survives rotation and process death: a half-answered destructive question should not vanish.
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Flattening every playlist is O(library). Do it when the library changes, never while scrolling.
    val trackCount = remember(model.tracksByPlaylist) { model.allTracks.size }
    // The expanded title is 36sp, so its bar has to grow with the user's font scale or it clips.
    val fontScale = LocalDensity.current.fontScale.coerceIn(1f, 2f)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { CollapsingTitle("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background,
                scrolledContainerColor = colors.background),
            expandedHeight = TopAppBarDefaults.LargeAppBarExpandedHeight * fontScale,
            // The scaffold already applied the status bar inset to this content.
            windowInsets = WindowInsets(0, 0, 0, 0),
            scrollBehavior = scrollBehavior,
        )
        Column(Modifier.verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A failed refresh keeps the previous library on screen; this says why it is stale.
            model.error?.let { ErrorCard(it, "Retry", quiet = model.offline, modifier = Modifier) { model.connect() } }

            GroupLabel("Connection")
            SettingsGroup {
                SettingsRow(shape = rowShape(0, 3), headline = "Tauon desktop", supporting = model.address.removePrefix("http://"),
                    trailing = { ConnectedBadge(model.offline) })
                SettingsRow(shape = rowShape(1, 3), headline = "Refresh library",
                    supporting = if (model.busy) model.progress.ifBlank { "Refreshing…" }
                        else if (model.offline) model.progress
                        else "$trackCount ${if (trackCount == 1) "track" else "tracks"} loaded",
                    enabled = !model.busy,
                    modifier = Modifier.clickable(enabled = !model.busy, onClickLabel = "Refresh library") { model.connect() })
                SettingsRow(shape = rowShape(2, 3), headline = "Disconnect",
                    supporting = "Stops playback and forgets this server",
                    headlineColor = colors.error,
                    modifier = Modifier.clickable(onClickLabel = "Disconnect") { confirmDisconnect = true })
            }

            GroupLabel("Storage")
            StorageGroup { confirmClear = true }

            GroupLabel("Appearance")
            AppearanceGroup(appearance)

            PrivacyNote()
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Remove all downloads?") },
        text = { Text("Songs you downloaded will stream again, and need Tauon to play.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; OfflineStore.removeAll(context) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    if (confirmDisconnect) DisconnectDialog(model.address, dismiss = { confirmDisconnect = false }) {
        confirmDisconnect = false
        disconnect()
    }
}

/**
 * Storage (#112, mockup 01): what the downloads hold, with Clear, and the quality they are kept at.
 * Downloads are kept across disconnecting, since offline is exactly when they are wanted.
 */
@Composable
private fun StorageGroup(clear: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val songs = DownloadMarks.marks.values.count { it == DownloadMark.Done }
    val used = PlayedCacheState.used
    val limit = PlayedCacheState.limit
    val full = cacheFull(used, limit)
    // Free space where the downloads live, read once per visit; it changes slowly.
    val free = remember { runCatching { android.os.StatFs(context.filesDir.path).availableBytes }.getOrDefault(0L) }
    // Offered only while a removable card is in and was found when the store opened; with none, the row
    // is simply not there (the user's decision).
    val card = remember { OfflineStore.get(context).card?.let { cardFolder(context) } }
    var onCard by remember { mutableStateOf(OfflineStore.storeOnCard(context)) }
    val rows = if (card != null) 5 else 4
    // Free space where new downloads go: the card's when they go there (#16 QA).
    val cardFreeSpace = remember(card) { card?.let { runCatching { android.os.StatFs(it.path).availableBytes }.getOrNull() } }
    StorageBar(DownloadMarks.bytes, used, if (onCard && cardFreeSpace != null) cardFreeSpace else free)
    SettingsGroup {
        SettingsRow(shape = rowShape(0, rows), headline = "Downloads",
            supporting = if (songs == 0) "None yet. Long-press a song, or use Download all on an album or artist."
                else "$songs ${if (songs == 1) "song" else "songs"} · ${formatBytes(DownloadMarks.bytes)}",
            trailing = { if (songs > 0) TextButton(onClick = clear) { Text("Clear") } })
        // Full is not a fault: the oldest songs make room. It is said plainly, next to the way to keep more.
        SettingsRow(shape = rowShape(1, rows), headline = "Played-song cache",
            supporting = (if (used == 0L) "Empty" else "${formatBytes(used)} of ${formatBytes(limit)}") +
                if (full) " · full, oldest songs make room" else "",
            trailing = { if (used > 0) TextButton(onClick = { OfflineStore.clearPlayed(context) }) { Text("Clear") } })
        // The limit is chosen right here, from four sizes side by side, rather than in a dialog.
        Surface(shape = rowShape(2, rows), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                Text("Cache limit", style = MaterialTheme.typography.bodyLarge,
                    color = if (full) colors.primary else colors.onSurface)
                Text("How much recent listening to keep", style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    CACHE_LIMITS.forEachIndexed { index, option ->
                        SegmentedButton(selected = option == limit, onClick = { OfflineStore.setCacheLimit(context, option) },
                            shape = SegmentedButtonDefaults.itemShape(index, CACHE_LIMITS.size), icon = {}) {
                            Text(formatBytes(option), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
        SettingsRow(shape = rowShape(3, rows), headline = "Download quality", supporting = "Opus, 84 kbps · set by Tauon for now")
        if (card != null) {
            val cardName = remember(card) { cardDescription(context, card) }
            val cardFree = remember(card) { runCatching { android.os.StatFs(card.path).availableBytes }.getOrDefault(0L) }
            SettingsRow(shape = rowShape(4, rows), headline = "Store on SD card",
                supporting = "$cardName · ${formatBytes(cardFree)} free",
                trailing = { Switch(checked = onCard, onCheckedChange = null) },
                modifier = Modifier.toggleable(value = onCard, role = Role.Switch) {
                    onCard = it; OfflineStore.setStoreOnCard(context, it)
                })
        }
    }
    if (card != null) Text("New downloads go to the card. Removing the card hides its downloads until it is back.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp))
    Text("The cache keeps Opus copies of songs you play, so they also play without Tauon. " +
        "Downloaded songs stay until you remove them. Lossless streams play from memory and never touch storage.",
        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp))
}

/**
 * What Muon keeps on the phone (mockup 01): one bar split into downloads, the played-song cache and
 * the free space left, with a legend.
 */
@Composable
private fun StorageBar(downloads: Long, cache: Long, free: Long) {
    val colors = MaterialTheme.colorScheme
    val total = (downloads + cache + free).coerceAtLeast(1)
    Surface(shape = RoundedCornerShape(GroupOuterCorner), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Muon uses ${formatBytes(downloads + cache)}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text("${formatBytes(free)} free", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                .background(colors.surfaceVariant)) {
                // Anything stored shows at least as a sliver (1.5% of the bar): a few megabytes of a
                // phone's gigabytes would otherwise draw nothing, and the bar would read as empty (#16 QA).
                val downloadShare = storageShare(downloads, total)
                val cacheShare = storageShare(cache, total)
                if (downloadShare > 0f) Box(Modifier.fillMaxHeight().weight(downloadShare).background(colors.primary))
                if (cacheShare > 0f) Box(Modifier.fillMaxHeight().weight(cacheShare).background(colors.primary.copy(alpha = 0.45f)))
                val rest = 1f - downloadShare - cacheShare
                if (rest > 0f) Spacer(Modifier.weight(rest))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(colors.primary, "Downloads")
                Legend(colors.primary.copy(alpha = 0.45f), "Played-song cache")
                Legend(colors.surfaceVariant, "Free", outlined = true)
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, outlined: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Free matches its card's own colour, so it is drawn with an outline to be seen at all.
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color)
            .then(if (outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(5.dp)) else Modifier))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * The bar gives its expanded slot `headlineMedium` and its collapsed slot `titleLarge`, and 2.0
 * wants 36sp expanded. This reads the style it was handed rather than the scroll position, so it
 * does not recompose as the bar collapses.
 */
@Composable
private fun CollapsingTitle(text: String) {
    val collapsed = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize
    Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = if (collapsed) MaterialTheme.typography.barTitle else MaterialTheme.typography.screenTitle)
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp))
}

/** A hairline between rows, so the group reads as one block of related settings. */
@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)

private fun rowShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) GroupOuterCorner else GroupInnerCorner,
    topEnd = if (index == 0) GroupOuterCorner else GroupInnerCorner,
    bottomStart = if (index == count - 1) GroupOuterCorner else GroupInnerCorner,
    bottomEnd = if (index == count - 1) GroupOuterCorner else GroupInnerCorner,
)

/**
 * One row. The whole row is the target, so any control it carries is passive: the modifier that
 * makes it clickable, selectable or toggleable is applied here, once.
 */
@Composable
private fun SettingsRow(shape: Shape, headline: String, supporting: String? = null,
    modifier: Modifier = Modifier, enabled: Boolean = true, headlineColor: Color = Color.Unspecified,
    trailing: @Composable (() -> Unit)? = null, leading: @Composable (() -> Unit)? = null) {
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().then(modifier)) {
        ListItem(
            // No fixed height: a long title or a large font scale grows the row instead of clipping.
            headlineContent = { Text(headline, color = if (!enabled) faded else headlineColor) },
            supportingContent = supporting?.let { { Text(it, color = if (!enabled) faded else Color.Unspecified) } },
            leadingContent = leading,
            trailingContent = trailing,
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun ConnectedBadge(offline: Boolean = false) {
    // Offline is not an error: the downloads still play. It is set apart by a quieter colour.
    Surface(color = if (offline) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (offline) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50)) {
        Text(if (offline) "Offline" else "Connected", style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun AppearanceGroup(appearance: AppearanceSettings) {
    val dynamicAvailable = dynamicColorAvailable(Build.VERSION.SDK_INT)
    // Show what will actually render: a device without dynamic colour cannot honour Material You.
    val shown = effectivePalette(appearance.palette, dynamicAvailable)
    SettingsGroup {
        PaletteChoice.entries.forEachIndexed { index, choice ->
            val enabled = choice != PaletteChoice.MaterialYou || dynamicAvailable
            SettingsRow(shape = rowShape(index, PaletteChoice.entries.size + 1),
                headline = paletteLabel(choice), supporting = paletteDescription(choice), enabled = enabled,
                // A radio mark, not a tint, so the choice is readable without relying on colour.
                trailing = { RadioButton(selected = shown == choice, onClick = null, enabled = enabled) },
                modifier = Modifier.selectable(selected = shown == choice, enabled = enabled,
                    role = Role.RadioButton) { appearance.choose(choice) })
        }
        SettingsRow(shape = rowShape(PaletteChoice.entries.size, PaletteChoice.entries.size + 1),
            headline = "Pure black", supporting = "Black backgrounds in dark mode",
            trailing = { Switch(checked = appearance.amoled, onCheckedChange = null) },
            modifier = Modifier.toggleable(value = appearance.amoled, role = Role.Switch) {
                appearance.chooseAmoled(it)
            })
    }
    if (!dynamicAvailable) Text("Material You needs Android 12 or newer, so this device uses the Muon palette.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp))
}

/** Disconnect clears the saved server and stops playback, so it asks first and names the server. */
@Composable
private fun DisconnectDialog(address: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Disconnect from Tauon?") },
        text = { Text("Playback stops, and Muon forgets $address. You'll need the address or a scan to reconnect.") },
        confirmButton = {
            TextButton(onClick = confirm) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun PrivacyNote() {
    HorizontalDivider()
    Text("A private connection", style = MaterialTheme.typography.titleMedium)
    Text("Tauon's remote API is for trusted LANs. It has no login or encryption over HTTP. Never expose port 7814 to the Internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Streams the original audio · Downloads keep Opus copies\nAndroid playback · Desktop playback stays independent", style = MaterialTheme.typography.bodySmall)
}

/** A stored amount's share of the storage bar: its true share, but never less than a visible sliver. */
internal fun storageShare(bytes: Long, total: Long): Float =
    if (bytes <= 0 || total <= 0) 0f else maxOf(bytes.toFloat() / total, 0.015f).coerceAtMost(1f)
