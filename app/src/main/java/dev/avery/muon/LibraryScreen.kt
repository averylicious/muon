package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * Songs or Playlists. Albums and Artists belong here too, and are deliberately absent until they
 * exist: a chip that opens nothing is worse than no chip.
 */
@Composable
internal fun LibraryChips(view: LibraryView, choose: (LibraryView) -> Unit) {
    // Deliberately plain: an earlier edge fade used an offscreen compositing layer and a DstOut
    // blend, which the user reported as a scroll regression. The chips overflow past the padding
    // instead, which costs nothing to draw.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryView.entries.forEach { choice ->
            LibraryChip(choice.name, view == choice) { choose(choice) }
        }
    }
}

/**
 * The playlists, as rows rather than chips. Empty ones are left out: they cannot be opened to
 * anything, and Tauon tends to accumulate them.
 */
@Composable
internal fun PlaylistRows(playlists: List<TauonPlaylist>, loading: Boolean, open: (String) -> Unit) {
    val listed = playlists.filter { it.count > 0 }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        if (listed.isEmpty()) item {
            // Full height so the list can still be pulled down to refresh.
            Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(if (loading) "Loading playlists…"
                    else "No playlists with music yet. Make one in Tauon, then refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else items(listed, key = { it.id }, contentType = { "playlist" }) { playlist ->
            ListItem(
                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text("${playlist.count} ${if (playlist.count == 1) "song" else "songs"}")
                },
                leadingContent = { MuonIcon("library") },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.clickable(onClickLabel = "Open playlist") { open(playlist.id) },
            )
        }
    }
}

/** The bar over one playlist's songs: its name, its size, and the way back to the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistBar(name: String, count: Int, back: () -> Unit) {
    TopAppBar(
        title = { Text(name, style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = back,
                modifier = Modifier.semantics { contentDescription = "Back to playlists" }) {
                MuonIcon("back")
            }
        },
        actions = {
            Text("$count", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        // The scaffold already applies the status bar inset to this content.
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun LibraryChip(label: String, selected: Boolean, select: () -> Unit) {
    FilterChip(selected, select, label = { Text(label, maxLines = 1) },
        // A check mark, so the selected chip is not distinguished by its fill colour alone.
        leadingIcon = if (selected) { { MuonIcon("check", Modifier.size(18.dp)) } } else null)
}
