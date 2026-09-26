package dev.avery.muon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Results plus enough state to tell "nothing typed" from "still searching" from "no matches". */
@Immutable
internal data class SearchResults(val tracks: List<TauonTrack> = emptyList(),
    val searching: Boolean = false, val completed: String = "")

@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, searching: Boolean) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Songs, artists, albums") }, leadingIcon = { MuonIcon("search") },
            singleLine = true, shape = RoundedCornerShape(18.dp),
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { onQuery("") }) { Text("Clear") } })
        // Reserved either way, so starting a search does not shift the results under the finger.
        Box(Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp)) {
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/**
 * Above a search's songs: the artists and albums it matched, each opening its page, so an album can
 * be found and played as a whole rather than one song at a time. Nothing is added when neither matched,
 * and the songs keep the screen to themselves.
 */
internal fun LazyListScope.searchCollection(artists: List<LibraryArtist>, albums: List<LibraryAlbum>,
    endpoint: ServerEndpoint?, openArtist: (LibraryArtist) -> Unit, openAlbum: (LibraryAlbum) -> Unit) {
    if (artists.isEmpty() && albums.isEmpty()) return
    if (artists.isNotEmpty()) {
        item(key = "search:artists", contentType = "label") { SectionHeading("Artists") }
        items(artists, key = { "search:${it.key}" }, contentType = { "artist" }) { artist ->
            ListItem(
                headlineContent = { Text(artistLabel(artist.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("Artist · ${artistSongCount(artist.tracks.size)}") },
                leadingContent = { ArtistAvatar(artist) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
                modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Open artist") { openArtist(artist) },
            )
        }
    }
    if (albums.isNotEmpty()) {
        item(key = "search:albums", contentType = "label") { SectionHeading("Albums") }
        item(key = "search:album-row", contentType = "albums") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums, key = { it.key }, contentType = { "album" }) { album ->
                    AlbumTile(album, endpoint, subtitle = artistLabel(displayCredits(album.artist))) { openAlbum(album) }
                }
            }
        }
    }
    item(key = "search:songs", contentType = "label") { SectionHeading("Songs") }
}
