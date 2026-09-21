package dev.avery.muon

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBar() {
    // The Refresh button is gone: pulling the list down refreshes it (#44). The action itself is
    // still reachable without the gesture, as a custom accessibility action on the list below.
    TopAppBar(title = { Text("Library", style = MaterialTheme.typography.titleLarge) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        // The scaffold already applies the status bar inset to this content.
        windowInsets = WindowInsets(0, 0, 0, 0))
}

/**
 * The library list, refreshed by pulling it down.
 *
 * [refreshing] is the model's own busy flag and [refresh] its existing reload, so this adds a
 * gesture rather than a second way to load a library. A pull gesture means nothing to a screen
 * reader and the Refresh button it replaced is gone, so the same action stays available as an
 * explicit accessibility action — declined while a refresh is already running, which is what the
 * disabled button used to express.
 */
@Composable
internal fun LibraryPane(refreshing: Boolean, refresh: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = refresh,
        modifier = Modifier.fillMaxSize().semantics {
            customActions = listOf(CustomAccessibilityAction("Refresh library") {
                if (refreshing) false else { refresh(); true }
            })
        },
        content = content,
    )
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
