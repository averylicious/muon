package dev.avery.muon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Material's large bar asks for this much height when expanded, before any font scaling. */
internal const val LIBRARY_HEADER = 152f

/** Below this much room for the list, a greeting is taking space the music needs. */
internal const val LIBRARY_MINIMUM_LIST = 240f

/**
 * How tall the greeting may be, or `null` when the window cannot afford one.
 *
 * The greeting is two lines of 36sp above a count, so it grows with the text, and so does the
 * space a useful stretch of list needs: both sides of the comparison scale together, at the size
 * the system actually reports. A tall portrait window affords the greeting; a short landscape or
 * split-screen one does not, and spending most of it on a welcome rather than on music would be
 * the wrong trade.
 */
internal fun libraryHeaderHeight(windowHeight: Float, fontScale: Float): Float? {
    val scale = fontScale.coerceAtLeast(1f)
    val header = LIBRARY_HEADER * scale
    return header.takeIf { windowHeight - it >= LIBRARY_MINIMUM_LIST * scale }
}

/**
 * The greeting, folding into a compact bar as the list scrolls up.
 *
 * The Refresh button is gone: pulling the list down refreshes it (#44). The action itself is
 * still reachable without the gesture, as a custom accessibility action on the list below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryHeader(tracks: Int, expandedHeight: Float?, titled: Boolean, offline: Boolean,
    scrollBehavior: TopAppBarScrollBehavior) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.background)
    // The scaffold already applies the status bar inset to this content.
    val insets = WindowInsets(0, 0, 0, 0)
    if (expandedHeight == null) {
        // Beside a rail, whose selected Library already names the screen, a bar saying so again
        // would spend a short window's height on nothing: the chips start at the top instead.
        if (!titled) return
        TopAppBar(title = { Text("Library", style = MaterialTheme.typography.barTitle) },
            colors = colors, windowInsets = insets)
    } else {
        LargeTopAppBar(
            title = { Greeting(tracks, offline) },
            colors = colors,
            expandedHeight = expandedHeight.dp,
            windowInsets = insets,
            scrollBehavior = scrollBehavior,
        )
    }
}

/**
 * Bold, as #40 decided: regular weight read wrong on a two-line greeting. The bar hands its
 * expanded slot `headlineMedium` and its collapsed slot `titleLarge`, so reading the style it was
 * given says which slot this is, without watching the scroll position from composition.
 */
@Composable
private fun Greeting(tracks: Int, offline: Boolean) {
    val collapsed = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize
    if (collapsed) {
        Text("Library", style = MaterialTheme.typography.barTitle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else Column {
        Text("Your music,\nnearby.", style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Offline, the songs are the ones on this phone, not the desktop's (#16 QA).
        if (tracks > 0) Text("$tracks ${if (tracks == 1) "track" else "tracks"} " + if (offline) "on this phone" else "from your desktop",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The top-level library: greeting, view chips, and the list under a pull to refresh.
 *
 * [bar] is the greeting's fold, owned by the caller with the lists' positions, so a page opened from
 * here and closed again returns to the same fold as well as the same row. Otherwise the greeting
 * unfolds on return and pushes the kept row down the screen.
 *
 * When the window is too short for the greeting it folds to a plain "Library" bar, and beside a
 * navigation rail ([titled] false) to nothing at all, so a phone on its side shows several rows.
 *
 * The collapsing bar's nested scroll sits *inside* the pull container, so an upward scroll folds
 * the greeting first and a downward one unfolds it before the pull begins: a drag at the top
 * restores the greeting, and only a further pull refreshes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTop(tracks: Int, view: LibraryView, choose: (LibraryView) -> Unit,
    refreshing: Boolean, refresh: () -> Unit, bar: TopAppBarState = rememberTopAppBarState(),
    titled: Boolean = true, offline: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = libraryHeaderHeight(maxHeight.value, LocalDensity.current.fontScale)
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(bar)
        Column(Modifier.fillMaxSize()) {
            LibraryHeader(tracks, expanded, titled, offline, scrollBehavior)
            LibraryChips(view, choose)
            LibraryPane(refreshing, refresh) {
                Box(Modifier.fillMaxSize().then(
                    if (expanded != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    else Modifier)) {
                    content()
                }
            }
        }
    }
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
 * Songs, Artists or Playlists. Albums belong here too, and are deliberately absent until they exist:
 * a chip that opens nothing is worse than no chip.
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
 * The line above a sortable list: how many there are, and how they are ordered. The order is a menu
 * rather than more chips, so it does not compete with the views above it, and the chosen order is
 * marked by a check, not by colour alone.
 */
@Composable
internal fun <T> SortBar(count: String?, options: List<T>, current: T, label: (T) -> String, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // No count where something above already gives it, as the Songs greeting does (#122).
        if (count != null) Text(count, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        Box {
            // The chevron says this opens a menu; without it the order read as a label (#122).
            TextButton(onClick = { open = true },
                modifier = Modifier.semantics { contentDescription = "Order: ${label(current)}" }) {
                Text(label(current), maxLines = 1)
                Spacer(Modifier.width(4.dp))
                MuonIcon("collapse", Modifier.size(18.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(label(option)) },
                        onClick = { open = false; choose(option) },
                        leadingIcon = {
                            if (option == current) MuonIcon("check", Modifier.size(18.dp))
                            else Spacer(Modifier.size(18.dp))
                        },
                        modifier = Modifier.semantics { selected = option == current })
                }
            }
        }
    }
}

/**
 * The playlists, as rows rather than chips. Empty ones are left out: they cannot be opened to
 * anything, and Tauon tends to accumulate them.
 *
 * [state] is owned by the caller, as for the artists, so the position outlives an open playlist; the
 * empty or loading message scrolls with its own state so it cannot clamp the kept position.
 */
@Composable
internal fun PlaylistRows(playlists: List<TauonPlaylist>, loading: Boolean, state: LazyListState,
    open: (String) -> Unit) {
    val listed = playlists.filter { it.count > 0 }
    if (listed.isEmpty()) LazyColumn(Modifier.fillMaxSize()) {
        item {
            // Full height so the list can still be pulled down to refresh.
            Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(if (loading) "Loading playlists…"
                    else "No playlists with music yet. Make one in Tauon, then refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else LazyColumn(Modifier.fillMaxSize().scrollIndicator(rememberScrollIndicator(state),
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)), state = state, contentPadding = PaddingValues(bottom = 12.dp)) {
        items(listed, key = { it.id }, contentType = { "playlist" }) { playlist ->
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

/**
 * The artists, one row each, in the order they first appear in the library. [artists] is null while
 * the library is still being grouped. Each row is announced by the artist's full name; the avatar's
 * initials are decoration.
 *
 * [state] is owned by the caller so the position outlives an open artist page. Rows are keyed, so a
 * refresh that moved the first visible artist finds it again; one that removed it keeps the index,
 * within the new length. The loading and empty message deliberately scrolls with its own state: a
 * moment of loading would otherwise clamp the kept position to its single row.
 */
@Composable
internal fun ArtistRows(artists: List<LibraryArtist>?, loading: Boolean, state: LazyListState,
    open: (LibraryArtist) -> Unit) {
    if (artists.isNullOrEmpty()) LazyColumn(Modifier.fillMaxSize()) {
        item {
            // Full height so the list can still be pulled down to refresh.
            Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(if (loading || artists == null) "Loading artists…"
                    else "No artists yet. Add local music in Tauon, then refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else LazyColumn(Modifier.fillMaxSize().scrollIndicator(rememberScrollIndicator(state),
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)), state = state, contentPadding = PaddingValues(bottom = 12.dp)) {
        items(artists, key = { it.key }, contentType = { "artist" }) { artist ->
            ListItem(
                headlineContent = {
                    Text(artistLabel(artist.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = { Text(artistSongCount(artist.tracks.size)) },
                // The circle that grows into the artist page's (motion pass 2).
                leadingContent = { ArtistAvatar(artist, modifier = sharedPicture(artistPictureKey(artist.key))) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.clickable(onClickLabel = "Open artist") { open(artist) },
            )
        }
    }
}

/**
 * Initials on one of the theme's tonal containers, or a generic artist icon for a blank name.
 * [side] is the circle's width at the default font scale; from 64 dp the initials are set large.
 */
@Composable
internal fun ArtistAvatar(artist: LibraryArtist, side: Dp = 40.dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (artistTone(artist.key)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    val initials = artistInitials(artist.name)
    // Grows with the text, so large fonts keep both initials inside the circle.
    val large = side >= 64.dp
    // Grows with the text so the initials fit, but a header circle only a little: at the largest font
    // it doubled to 192 dp and squeezed the artist's name into a column (#16 QA).
    val scaled = side * LocalDensity.current.fontScale.coerceIn(1f, if (large) 1.25f else 2f)
    Box(modifier.size(scaled).clip(CircleShape).background(container).clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides content) {
            if (initials.isEmpty()) MuonIcon("artist", Modifier.size(side / 2))
            else Text(initials, style = if (large) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.labelLarge,
                fontWeight = if (large) FontWeight.Medium else null, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The bar over one playlist's or one artist's songs: its name, its size, and the way back to the
 * list it was opened from. A null [count] is not yet known and shows nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistBar(name: String, count: Int?, backLabel: String = "Back to playlists", back: () -> Unit) {
    TopAppBar(
        title = { Text(name, style = MaterialTheme.typography.barTitle,
            maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = back,
                modifier = Modifier.semantics { contentDescription = backLabel }) {
                MuonIcon("back")
            }
        },
        actions = {
            if (count != null) Text("$count", style = MaterialTheme.typography.labelLarge,
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
