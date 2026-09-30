package dev.avery.muon

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ConnectScreen(model: LibraryModel, allowLocalNetwork: () -> Unit) {
    val context = LocalContext.current
    var nsd by remember { mutableStateOf(DiscoverySnapshot(DiscoveryStatus.IDLE)) }
    val discovery = remember { ServerDiscovery(context, { _, _ -> }, {}, { nsd = it }) }
    DisposableEffect(discovery) { onDispose { discovery.stop() } }
    // Discovery, and a direct question to every host on the network, which finds Tauon even when it
    // is not advertising itself. Null while the probe is still asking.
    var probe by remember { mutableStateOf<List<DiscoveredServer>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    // Check the actual grant as well as the observable resume/prompt snapshot.
    val allowed = LocalNetworkState.granted && localNetworkGranted(context)
    val scan = combineDiscovery(nsd, probe, allowed)
    // Downloads open without a server (#112): offered whenever any are on the phone.
    LaunchedEffect(Unit) { OfflineStore.get(context) }
    val downloaded = DownloadMarks.marks.values.count { it == DownloadMark.Done }
    // Looks for Tauon as soon as there is nothing to show (#39): first run, after Disconnect, or when
    // the saved address stopped answering. Exactly one answer is connected to without asking, once
    // per visit, and not over an address the user is typing; several are listed to choose from.
    var autoTried by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf(false) }
    // Nothing is looked for until Android 17 allows it; granting access starts the scan.
    LaunchedEffect(discovery, round, allowed) {
        val granted = localNetworkGranted(context)
        if (!allowed || !granted) {
            discovery.stop()
            nsd = DiscoverySnapshot(DiscoveryStatus.IDLE)
            probe = null
            if (!granted) LocalNetworkState.granted = false
            return@LaunchedEffect
        }
        discovery.start()
        probe = null
        probe = LanProbe.find(context)
    }
    LaunchedEffect(scan, model.busy, allowed) {
        if (!allowed || model.busy) return@LaunchedEffect
        autoConnectTarget(scan, autoTried, typed)?.let { server ->
            autoTried = true
            model.address = server.origin
            model.connect()
        }
    }
    val colors = MaterialTheme.colorScheme
    // Mockup 17: a tinted page with the bold greeting — the one deliberate exception to the app's
    // medium titles — then the address card, what was found on this network, and what is on this phone.
    Column(Modifier.fillMaxSize().background(colors.surfaceContainer).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text("Bring your\nlibrary along.", style = MaterialTheme.typography.screenTitle, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 40.dp))
        Text("Play your Tauon library on this phone, over your own network.",
            style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 24.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Server address", style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                OutlinedTextField(model.address, { model.address = it; typed = true },
                    placeholder = { Text("192.168.1.10:7814") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                    enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                // Without local network access, Connect asks for it first and connects once granted.
                Button(onClick = { if (allowed) model.connect() else allowLocalNetwork() },
                    enabled = !model.busy && model.address.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (model.busy) "Connecting…" else "Connect", style = MaterialTheme.typography.titleMedium)
                }
                if (model.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.progress.isNotEmpty()) Text(model.progress, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
            }
        }
        if (!allowed) {
            // Android 17 asks before apps reach devices on the local network; say why before it does.
            Surface(shape = RoundedCornerShape(24.dp), color = colors.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Allow Muon on your network", style = MaterialTheme.typography.titleMedium, color = colors.onSecondaryContainer)
                    Text("Android asks before an app reaches devices on your Wi-Fi. Muon only talks to Tauon, " +
                        "to find it, load your library and play your music.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSecondaryContainer)
                    FilledTonalButton(onClick = allowLocalNetwork, modifier = Modifier.align(Alignment.End)) {
                        Text(if (LocalNetworkState.denied) "Open settings" else "Allow")
                    }
                }
            }
        } else model.error?.let { ErrorCard(it, modifier = Modifier.padding(top = 12.dp)) }

        GroupHeading("On this network")
        val found = scan.servers
        val rows = found.size + 1
        found.forEachIndexed { i, server ->
            // Choosing a server connects to it: there is nothing left to confirm.
            GroupRow(groupShape(i, rows), onClick = { model.address = server.origin; model.connect() },
                enabled = !model.busy, label = "Connect") {
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(server.origin.removePrefix("http://"), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("Use", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }
        val searching = scan.status == DiscoveryStatus.SEARCHING
        GroupRow(groupShape(rows - 1, rows), onClick = { round++ }, enabled = allowed && !searching && !model.busy, label = "Scan again") {
            if (searching) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Looking for Tauon…", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp))
            } else Column {
                Text(if (allowed) "Scan again" else "Network access needed",
                    style = MaterialTheme.typography.titleMedium, color = colors.primary)
                if (!allowed) Text("Allow local network access above to look for Tauon.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                else if (found.isEmpty()) Text("Nothing found. Check that Tauon's remote control is on, or type its address.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }

        // Downloads open without a server (#112).
        if (downloaded > 0) {
            GroupHeading("On this phone")
            GroupRow(groupShape(0, 1), onClick = { model.listenOffline() }, enabled = !model.busy, label = "Open my downloads") {
                Column(Modifier.weight(1f)) {
                    Text("My downloads", style = MaterialTheme.typography.titleMedium)
                    Text("$downloaded ${if (downloaded == 1) "song plays" else "songs play"} without Tauon",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Text("Open", style = MaterialTheme.typography.labelLarge, color = colors.primary)
            }
        }

        Text("Tauon's API has no login or encryption. Only connect on a network you trust.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 28.dp))
    }
}

@Composable
private fun GroupHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 28.dp, bottom = 8.dp))
}

/** Rows of one group touch with small inner corners and round only at the group's ends, as in Settings. */
private fun groupShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 24.dp else 4.dp, topEnd = if (index == 0) 24.dp else 4.dp,
    bottomStart = if (index == count - 1) 24.dp else 4.dp, bottomEnd = if (index == count - 1) 24.dp else 4.dp)

@Composable
private fun GroupRow(shape: RoundedCornerShape, onClick: () -> Unit, enabled: Boolean, label: String,
    content: @Composable RowScope.() -> Unit) {
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(Modifier.clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
