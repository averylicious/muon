package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * One artist (mockup 06): initials in a large circle, since Tauon has no artist pictures, beside the
 * name and "N albums, M songs"; Play, filled, and Shuffle, tonal; a row of the albums they appear on;
 * then their songs. [artist] is null while this library's artists are still being grouped, when the
 * page waits with its [name] and Back still works. [state] is the caller's, so the page keeps its
 * place while one of its albums is open.
 */
@Composable
internal fun ArtistPage(artist: LibraryArtist?, name: String, albums: List<LibraryAlbum>, endpoint: ServerEndpoint?,
    currentId: String?, playing: Boolean, ready: Boolean, state: LazyListState, backLabel: String = "Back to artists",
    actions: (TauonTrack) -> Unit, back: () -> Unit,
    playAll: (shuffle: Boolean) -> Unit, openAlbum: (LibraryAlbum) -> Unit, play: (TauonTrack) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val playable = artist?.tracks?.any { it.playable } == true && ready
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "back", contentType = "back") {
            IconButton(onClick = back, modifier = Modifier.padding(start = 8.dp)
                .semantics { contentDescription = backLabel }) { MuonIcon("back") }
        }
        item(key = "header", contentType = "header") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtistAvatar(artist ?: LibraryArtist("", name, emptyList()), side = 96.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(artistLabel(artist?.name ?: name), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    if (artist != null) Text(artistSummary(albums.size, artist.tracks.size),
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        item(key = "actions", contentType = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { playAll(false) }, enabled = playable, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    MuonIcon("play", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Play")
                }
                FilledTonalButton(onClick = { playAll(true) }, enabled = playable, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    MuonIcon("shuffle", Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Shuffle")
                }
            }
        }
        if (artist != null) item(key = "download", contentType = "download") { DownloadAll(artist.tracks, endpoint) }
        if (artist == null) {
            item(key = "waiting", contentType = "waiting") {
                Text("Loading artist…", color = colors.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            return@LazyColumn
        }
        if (albums.isNotEmpty()) {
            item(key = "albums-label", contentType = "label") { SectionHeading("Albums") }
            item(key = "albums", contentType = "albums") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(albums, key = { it.key }, contentType = { "album" }) { album ->
                        AlbumTile(album, endpoint) { openAlbum(album) }
                    }
                }
            }
        }
        item(key = "songs-label", contentType = "label") { SectionHeading("Songs") }
        itemsIndexed(artist.tracks, key = { i, t -> "${t.id}#$i" }, contentType = { _, _ -> "track" }) { _, track ->
            // Every song here is theirs, so the line under it says only where it comes from; a guest
            // appearance still names the others credited.
            val credits = displayCredits(track.artist)
            val subtitle = remember(track, artist.name) {
                listOfNotNull(credits.takeIf { !it.equals(displayCredits(artist.name), ignoreCase = true) && it.isNotBlank() },
                    track.album.trim().ifBlank { null }).joinToString(" · ")
            }
            TrackRow(track, endpoint, currentId == "${endpoint?.origin}/${track.id}", playing, ready,
                subtitle = if (track.playable) subtitle else trackSubtitle(track.artist, track.album, false),
                actions = { actions(track) }) { play(track) }
        }
    }
}

@Composable
internal fun SectionHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp).semantics { heading() })
}

/** One album in a row of them: its cover, title and a line under it, opening the whole album. */
@Composable
internal fun AlbumTile(album: LibraryAlbum, endpoint: ServerEndpoint?,
    subtitle: String = "${album.tracks.size} ${if (album.tracks.size == 1) "song" else "songs"}",
    modifier: Modifier = Modifier.width(148.dp), open: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Open album", onClick = open)) {
        Artwork(albumArt(endpoint, album), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
        Text(albumLabel(album.title), style = MaterialTheme.typography.titleSmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 8.dp))
        Text(subtitle, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
    }
}
