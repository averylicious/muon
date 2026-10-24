package dev.avery.muon

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An album's cover: its first track's larger picture, which the disk cache keeps (#113). */
internal fun albumArt(endpoint: ServerEndpoint?, album: LibraryAlbum): String? =
    album.tracks.firstOrNull()?.let { endpoint?.url("/api1/pic/medium/${it.id}") }

/**
 * The albums as a grid of covers (mockup 02), as many columns as fit at about 160 dp each, so two on
 * a phone and more on a tablet. [albums] is null while the library is still being grouped. [state] is
 * owned by the caller so the position outlives an open album, as the lists' positions do (#105).
 * [recent], the albums played lately, leads the grid as Jump back in when there are enough of them.
 */
@Composable
internal fun AlbumGrid(albums: List<LibraryAlbum>?, loading: Boolean, endpoint: ServerEndpoint?,
    state: LazyGridState, recent: List<LibraryAlbum> = emptyList(), sortBar: (@Composable () -> Unit)? = null,
    open: (LibraryAlbum) -> Unit) {
    if (albums.isNullOrEmpty()) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalUnderBars.current)) {
        item {
            // Full height so the grid can still be pulled down to refresh.
            Box(Modifier.fillParentMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(if (loading || albums == null) "Loading albums…"
                    else "No albums yet. Add local music in Tauon, then refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), state = state,
        // Each tile carries 8 dp of its own padding, so a press lights a box with room around the
        // title; the grid's gaps shrink to match, and the covers sit exactly where they did.
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 8.dp + LocalUnderBars.current)) {
        if (recent.size >= RECENT_ALBUMS_SHOWN_FROM) jumpBackIn(recent, endpoint, open)
        // Under Jump back in rather than above it (the Refined mockup), scrolling with the grid.
        sortBar?.let { bar -> item(key = "sort", span = { GridItemSpan(maxLineSpan) }, contentType = "sort") { bar() } }
        items(albums, key = { it.key }, contentType = { "album" }) { album ->
            Column(springyClick("Open album", RoundedCornerShape(24.dp)) { openWhole(state, album, open) }.padding(8.dp)) {
                // The cover that grows into the album page's (motion pass 2).
                Artwork(albumArt(endpoint, album), sharedPicture(albumPictureKey(album.key))
                    .fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                Text(albumLabel(album.title), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 8.dp))
                Text(artistLabel(displayCredits(album.artist)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
            }
        }
    }
}

/**
 * One album (mockup 05): its cover beside the title, artist and length; Play, filled, and Shuffle,
 * tonal; then its songs, numbered, in the order Tauon lists them. [album] is null while this library's
 * albums are still being grouped, when the page waits with its [title] and Back still works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumPage(album: LibraryAlbum?, title: String, endpoint: ServerEndpoint?, currentId: String?,
    playing: Boolean, ready: Boolean, backLabel: String = "Back to albums", actions: (TauonTrack) -> Unit, back: () -> Unit, playAll: (shuffle: Boolean) -> Unit, play: (TauonTrack) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val playable = album?.tracks?.any { it.playable } == true && ready
    val state = rememberLazyListState()
    val toolbarRoom = if (Expressive.motion) PAGE_TOOLBAR_ROOM else 0.dp
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state,
            contentPadding = PaddingValues(bottom = 16.dp + LocalUnderBars.current + toolbarRoom)) {
            item(key = "back", contentType = "back") {
                IconButton(onClick = back, modifier = Modifier.padding(start = 8.dp)
                    .semantics { contentDescription = backLabel }) { MuonIcon("back") }
            }
            item(key = "header", contentType = "header") {
                // With Expressive motion the cover leads, large and centred, with the title and a line
                // of artist and length under it (the Refined mockup).
                if (Expressive.motion) Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(album?.let { albumArt(endpoint, it) },
                        (album?.let { sharedPicture(albumPictureKey(it.key)) } ?: Modifier).fillMaxWidth(0.62f).aspectRatio(1f)
                            .clip(RoundedCornerShape(32.dp)))
                    Text(albumLabel(album?.title ?: title), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp))
                    if (album != null) Text("${artistLabel(displayCredits(album.artist))} · ${albumSummary(album.tracks)}",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                } else Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(album?.let { albumArt(endpoint, it) },
                        (album?.let { sharedPicture(albumPictureKey(it.key)) } ?: Modifier).size(140.dp).clip(RoundedCornerShape(16.dp)))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(albumLabel(album?.title ?: title), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (album != null) {
                            Text(artistLabel(displayCredits(album.artist)), style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp))
                            Text(albumSummary(album.tracks), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            item(key = "actions", contentType = "actions") { PlayAllButtons(playable, playAll, album?.tracks.orEmpty(), endpoint) }
            if (album != null) item(key = "download", contentType = "download") { DownloadAll(album.tracks, endpoint) }
            if (album == null) item(key = "waiting", contentType = "waiting") {
                Text("Loading album…", color = colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            } else itemsIndexed(album.tracks, key = { i, t -> "${t.id}#$i" }, contentType = { _, _ -> "track" }) { i, track ->
                val current = currentId == "${endpoint?.origin}/${track.id}"
                // With Expressive motion the tracks are one joined group, numbered (the Refined mockup).
                val place = if (Expressive.motion) groupPlace(i, album.tracks.size) else null
                val shape = place?.let { groupShape(it, current) } ?: RoundedCornerShape(16.dp)
                val ground by animateColorAsState(when {
                    current -> colors.secondaryContainer
                    place != null -> colors.surfaceContainerLow
                    else -> Color.Transparent
                }, motionMedium(), label = "track ground")
                // The playing song sits on its own tonal surface, its number replaced by the moving
                // equalizer, so it stands out from the list rather than only changing colour.
                Row(Modifier.fillMaxWidth()
                    .padding(start = place?.let { GROUP_INSET } ?: 12.dp, end = place?.let { GROUP_INSET } ?: 12.dp,
                        top = if (place == null || place.startsGroup) 0.dp else GROUP_GAP)
                    .heightIn(min = 56.dp)
                    .clip(shape)
                    .background(ground)
                    .combinedClickable(enabled = track.playable && ready, onClickLabel = "Play",
                        onLongClickLabel = "Song actions", onLongClick = { actions(track) }) { play(track) }
                    .padding(start = 12.dp, end = 0.dp, top = 8.dp, bottom = 8.dp)
                    .semantics { if (current) stateDescription = "Now playing" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.widthIn(min = 28.dp), contentAlignment = Alignment.CenterStart) {
                        if (current) NowPlayingBars(playing)
                        // Counted down the page (the user's choice): an album the library holds only part of would
                        // otherwise read 2, 12 or skip a number where its tags leave gaps.
                        else Text("${i + 1}", style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(track.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (current) colors.onSecondaryContainer else if (track.playable) colors.onSurface else colors.onSurfaceVariant,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium)
                        // Only a guest artist is worth a line: the album's own artist is already above.
                        val credits = displayCredits(track.artist)
                        if (credits.isNotBlank() && !credits.equals(displayCredits(album.artist), ignoreCase = true))
                            Text(credits, style = MaterialTheme.typography.bodySmall,
                                color = if (current) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(formatTime(track.durationMs), style = MaterialTheme.typography.labelSmall,
                        color = if (current) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 44.dp))
                    SongMenuButton(track.title, track.artist,
                        if (current) colors.onSecondaryContainer else colors.onSurfaceVariant) { actions(track) }
                }
            }
        }
        // Items: Back 0, header 1, then Play and Shuffle at 2.
        PageToolbar(state, actionsIndex = 2, enabled = playable, backLabel = backLabel, back = back, playAll = playAll)
    }
}

/**
 * Opens an album from the grid, first bringing its tile fully into view when part of it is hidden
 * under the sort bar above the grid. The cover flies from the whole tile, and Back flies it home to
 * the whole tile; otherwise it flew over the bar and the view buttons to the hidden part, and snapped
 * under the bar as it landed (the user's recording on the Pixel, Canary .344). Applied at the next
 * layout, the same one the flight measures from.
 */
private fun openWhole(state: LazyGridState, album: LibraryAlbum, open: (LibraryAlbum) -> Unit) {
    val tile = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == album.key }
    if (tile != null && tileHiddenAbove(tile.offset.y)) state.requestScrollToItem(tile.index)
    open(album)
}

/** Whether a grid tile at [offsetY] from the top of the grid's viewport starts above it, partly hidden. */
internal fun tileHiddenAbove(offsetY: Int): Boolean = offsetY < 0

/** Play, filled, and Shuffle, tonal: the pair under an album's, an artist's or a playlist's header. */
@Composable
internal fun PlayAllButtons(enabled: Boolean, playAll: (shuffle: Boolean) -> Unit,
    saveTracks: List<TauonTrack> = emptyList(), endpoint: ServerEndpoint? = null) {
    // The Canary experiment: Play, Shuffle and Save copies as one connected group (issue #661), round
    // at the outer ends and tight between, each fully round while pressed.
    if (Expressive.motion) { ConnectedPageActions(enabled, playAll, saveTracks, endpoint); return }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { playAll(false) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            MuonIcon("play", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Play")
        }
        FilledTonalButton(onClick = { playAll(true) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            MuonIcon("shuffle", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Shuffle")
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectedPageActions(enabled: Boolean, playAll: (shuffle: Boolean) -> Unit,
    saveTracks: List<TauonTrack>, endpoint: ServerEndpoint?) {
    val context = LocalContext.current
    val saveable = remember(saveTracks) { saveTracks.filter { it.playable } }
    val canSave = endpoint != null && saveable.isNotEmpty()
    val outer = 26.dp
    val inner = 8.dp
    val pressed = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        val playPress = remember { MutableInteractionSource() }
        val shufflePress = remember { MutableInteractionSource() }
        Button(onClick = { playAll(false) }, enabled = enabled, interactionSource = playPress,
            modifier = pressedWeight(playPress, 1.3f).heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(RoundedCornerShape(outer, inner, inner, outer), pressed)) {
            MuonIcon("play", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Play")
        }
        FilledTonalButton(onClick = { playAll(true) }, enabled = enabled, interactionSource = shufflePress,
            modifier = pressedWeight(shufflePress, 1f).heightIn(min = 56.dp),
            shapes = ButtonDefaults.shapes(if (canSave) RoundedCornerShape(inner) else RoundedCornerShape(inner, outer, outer, inner), pressed)) {
            MuonIcon("shuffle", Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Shuffle")
        }
        if (canSave) FilledTonalButton(onClick = { endpoint?.let { OfflineStore.add(context, it, saveable) } },
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.width(60.dp).heightIn(min = 56.dp).semantics { contentDescription = "Save copies" },
            shapes = ButtonDefaults.shapes(RoundedCornerShape(inner, outer, outer, inner), pressed)) {
            MuonIcon("download", Modifier.size(22.dp))
        }
    }
}
