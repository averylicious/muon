package dev.avery.muon

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBar(busy: Boolean, refresh: () -> Unit) {
    TopAppBar(title = { Text("Library", style = MaterialTheme.typography.titleLarge) },
        actions = { TextButton(onClick = refresh, enabled = !busy) { Text("Refresh") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        // The scaffold already applies the status bar inset to this content.
        windowInsets = WindowInsets(0, 0, 0, 0))
}

@Composable
internal fun PlaylistChips(playlists: List<TauonPlaylist>, total: Int, selected: String?, select: (String?) -> Unit) {
    // Deliberately plain: an earlier edge fade used an offscreen compositing layer and a DstOut
    // blend, which the user reported as a scroll regression. The chips overflow past the padding
    // instead, which costs nothing to draw.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryChip("All music · $total", selected == null) { select(null) }
        playlists.forEach { p -> LibraryChip("${p.name} · ${p.count}", selected == p.id) { select(p.id) } }
    }
}

@Composable
private fun LibraryChip(label: String, selected: Boolean, select: () -> Unit) {
    FilterChip(selected, select, label = { Text(label, maxLines = 1) },
        // A check mark, so the selected chip is not distinguished by its fill colour alone.
        leadingIcon = if (selected) { { MuonIcon("check", Modifier.size(18.dp)) } } else null)
}
