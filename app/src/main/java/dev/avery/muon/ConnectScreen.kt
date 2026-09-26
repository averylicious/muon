package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var scan by remember { mutableStateOf(DiscoverySnapshot(DiscoveryStatus.IDLE)) }
    var discoveryMessage by remember { mutableStateOf("") }
    val discovery = remember { ServerDiscovery(context, { _, _ -> }, { discoveryMessage = it }, { scan = it }) }
    DisposableEffect(discovery) { onDispose { discovery.stop() } }
    // Looks for Tauon as soon as there is nothing to show (#39): first run, after Disconnect, or when
    // the saved address stopped answering. Exactly one answer is connected to without asking, once
    // per visit, and not over an address the user is typing; several are listed to choose from.
    var autoTried by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(discovery) { discovery.start() }
    LaunchedEffect(scan, model.busy) {
        if (model.busy) return@LaunchedEffect
        autoConnectTarget(scan, autoTried, typed)?.let { server ->
            autoTried = true
            model.address = server.origin
            model.connect()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(18.dp))
        Text("MUON", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Text("Bring your\nlibrary along.", style = MaterialTheme.typography.screenTitle,
            fontWeight = FontWeight.Bold)
        Text("Stream your Tauon collection to this device. Original audio, your playlists, wherever your LAN reaches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingsCard("Connect to Tauon desktop") {
            Text("Enable remote control in Tauon and restart it. Keep both devices on the same trusted LAN.")
            OutlinedTextField(model.address, { model.address = it; typed = true }, label = { Text("Server address") },
                placeholder = { Text("192.168.1.10:7814") }, singleLine = true,
                enabled = !model.busy, modifier = Modifier.fillMaxWidth())
            Button(onClick = { model.connect() }, enabled = !model.busy && model.address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (model.busy) "Connecting…" else "Connect")
            }
            if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall)
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        model.error?.let { ErrorCard(it, modifier = Modifier) }
        SettingsCard("Find Tauon on my LAN") {
            Text("Discovery needs Tauon to advertise itself. Typing the address always works.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { discovery.start() }, enabled = !model.busy && scan.status != DiscoveryStatus.SEARCHING,
                modifier = Modifier.fillMaxWidth()) { Text(if (scan.status == DiscoveryStatus.IDLE) "Scan this network" else "Scan again") }
            if (scan.status == DiscoveryStatus.SEARCHING) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (discoveryMessage.isNotEmpty()) Text(discoveryMessage, style = MaterialTheme.typography.bodySmall)
            // Choosing a server connects to it: there is nothing left to confirm.
            scan.servers.forEach { server ->
                DiscoveredServer(server.name, server.origin, enabled = !model.busy) { model.address = server.origin; model.connect() }
            }
        }
        PrivacyNote()
    }
}

@Composable
private fun DiscoveredServer(name: String, url: String, enabled: Boolean, use: () -> Unit) {
    // A found server is a list entry, not a button with two lines of text crammed into it.
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = "Connect", onClick = use)
        .padding(vertical = 10.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("Connect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}
