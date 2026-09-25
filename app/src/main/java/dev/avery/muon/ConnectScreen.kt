package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectScreen(model: LibraryModel) {
    val context = LocalContext.current
    var discovered by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var discoveryMessage by remember { mutableStateOf("") }
    val discovery = remember { ServerDiscovery(context, { name, url -> discovered = discovered + (url to name) }, { discoveryMessage = it }) }
    DisposableEffect(discovery) { onDispose { discovery.stop() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("MUON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("Bring your\nlibrary along.", style = MaterialTheme.typography.screenTitle,
            fontWeight = FontWeight.Bold)
        Text("Stream your Tauon collection to this device. Original audio, your playlists, wherever your LAN reaches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsCard("Connect to Tauon desktop") {
            Text("Enable remote control in Tauon and restart it. Keep both devices on the same trusted LAN.")
            OutlinedTextField(model.address, { model.address = it }, label = { Text("Server address") },
                placeholder = { Text("192.168.1.10:7814") }, singleLine = true,
                enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.connect() }, enabled = !model.busy && model.address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "Connecting…" else "Connect")
            }
            if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall)
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        model.error?.let { ErrorCard(it) }
        SettingsCard("Find Tauon on my LAN") {
            Text("Discovery needs Tauon to advertise itself. Typing the address always works.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { discovered = emptyMap(); discovery.start() }, enabled = !model.busy,
                modifier = Modifier.fillMaxWidth()) { Text("Scan this network") }
            if (discoveryMessage.isNotEmpty()) Text(discoveryMessage, style = MaterialTheme.typography.bodySmall)
            discovered.forEach { (url, name) -> DiscoveredServer(name, url) { model.address = url } }
        }
        PrivacyNote()
    }
}

@Composable
private fun DiscoveredServer(name: String, url: String, use: () -> Unit) {
    // A found server is a list entry, not a button with two lines of text crammed into it.
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = use)
        .padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Use", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
