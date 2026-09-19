package dev.avery.muon

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(model: LibraryModel, appearance: AppearanceSettings, disconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("MUON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        SettingsCard("Tauon desktop · connected") {
            Text(model.address, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { model.connect() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "Refreshing…" else "Refresh connection & library")
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = disconnect) { Text("Disconnect & stop playback") }
        }
        model.error?.let { ErrorCard(it) }
        AppearanceSection(appearance)
        PrivacyNote()
    }
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
private fun AppearanceSection(appearance: AppearanceSettings) {
    val dynamicAvailable = dynamicColorAvailable(Build.VERSION.SDK_INT)
    // Show what will actually render: a device without dynamic colour cannot honour Material You.
    val shown = effectivePalette(appearance.palette, dynamicAvailable)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text("Light and dark still follow your system setting. This chooses where the colours come from.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            PaletteChoice.entries.forEach { choice ->
                PaletteOption(choice, shown == choice,
                    enabled = choice != PaletteChoice.MaterialYou || dynamicAvailable) { appearance.choose(choice) }
            }
            if (!dynamicAvailable) Text("Material You needs Android 12 or newer, so this device uses the Muon palette.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .toggleable(value = appearance.amoled, role = Role.Switch) { appearance.chooseAmoled(it) }
                .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Pure black", style = MaterialTheme.typography.bodyLarge)
                    Text("Backgrounds go fully black on OLED screens. Accents are unchanged, and this only applies while your system is in dark mode.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = appearance.amoled, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun PaletteOption(choice: PaletteChoice, selected: Boolean, enabled: Boolean, select: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = select)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // A radio mark, not a tint, so the choice is readable without relying on colour.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(paletteLabel(choice), style = MaterialTheme.typography.bodyLarge)
            Text(paletteDescription(choice), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PrivacyNote() {
    HorizontalDivider()
    Text("A private connection", style = MaterialTheme.typography.titleMedium)
    Text("Tauon's remote API is for trusted LANs. It has no login or encryption over HTTP. Never expose port 7814 to the Internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Direct original audio · No transcoding\nAndroid playback · Desktop playback stays independent", style = MaterialTheme.typography.bodySmall)
}
