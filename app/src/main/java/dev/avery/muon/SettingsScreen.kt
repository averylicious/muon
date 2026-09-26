package dev.avery.muon

import android.os.Build
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
import androidx.compose.ui.Modifier
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
            model.error?.let { ErrorCard(it) }

            GroupLabel("Connection")
            SettingsGroup {
                SettingsRow(shape = rowShape(0, 3), headline = "Tauon desktop", supporting = model.address,
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
    val songs = DownloadMarks.marks.values.count { it == DownloadMark.Done }
    SettingsGroup {
        SettingsRow(shape = rowShape(0, 2), headline = "Downloads",
            supporting = if (songs == 0) "None yet. Long-press a song, or use Download all on an album or artist."
                else "$songs ${if (songs == 1) "song" else "songs"} · ${formatBytes(DownloadMarks.bytes)}",
            trailing = { if (songs > 0) TextButton(onClick = clear) { Text("Clear") } })
        SettingsRow(shape = rowShape(1, 2), headline = "Download quality", supporting = "Opus, 84 kbps · set by Tauon for now")
    }
    Text("Downloaded songs play from the phone, without Tauon. Lossless streams play from memory and never touch storage.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp))
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
