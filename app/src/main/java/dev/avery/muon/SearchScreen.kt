package dev.avery.muon

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Results plus enough state to tell "nothing typed" from "still searching" from "no matches". */
@Immutable
internal data class SearchResults(val tracks: List<TauonTrack> = emptyList(),
    val searching: Boolean = false, val completed: String = "")

/**
 * The Search tab (#48, mockups 07 to 09): a Material 3 search bar, a filled pill at rest, over a
 * browse of the library — its biggest artists and newest albums — since Muon keeps no search history.
 * Tapping the bar expands it to fill the screen with the keyboard up: before typing it suggests from
 * the library, and once there is a query it shows [results]. Back, or the arrow, returns to the bar
 * and keeps the query; the cross empties it.
 *
 * [expanded] is the caller's, so a page opened from the results comes back to them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreen(query: String, onQuery: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit,
    searching: Boolean, topArtists: List<LibraryArtist>, newAlbums: List<LibraryAlbum>, endpoint: ServerEndpoint?,
    openArtist: (LibraryArtist) -> Unit, openAlbum: (LibraryAlbum) -> Unit, results: @Composable () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    // Leaving for a page puts the keyboard away first, so it is not waiting over the page.
    val toArtist = { a: LibraryArtist -> keyboard?.hide(); openArtist(a) }
    val toAlbum = { a: LibraryAlbum -> keyboard?.hide(); openAlbum(a) }
    val inset by animateDpAsState(if (expanded) 0.dp else 16.dp, motionMedium(), label = "search bar inset")
    Box(Modifier.fillMaxSize()) {
        if (!expanded) SearchBrowse(topArtists, newAlbums, endpoint, toArtist, toAlbum,
            Modifier.fillMaxSize().padding(top = SEARCH_BAR_ROOM))
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(query = query, onQueryChange = onQuery,
                    onSearch = { keyboard?.hide() }, expanded = expanded, onExpandedChange = onExpanded,
                    placeholder = { Text("Search songs, artists, albums", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (expanded) IconButton(onClick = { onExpanded(false) },
                            modifier = Modifier.semantics { contentDescription = "Back" }) { MuonIcon("back") }
                        else MuonIcon("search")
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") },
                            modifier = Modifier.semantics { contentDescription = "Clear search" }) { MuonIcon("close") }
                    })
            },
            expanded = expanded, onExpandedChange = onExpanded,
            // The tab already sits inside the app's insets.
            windowInsets = WindowInsets(0),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = inset),
        ) {
            // Reserved either way, so starting a search does not shift the results under the finger.
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (searching) LinearProgressIndicator(Modifier.fillMaxSize())
            }
            if (query.isBlank()) SearchSuggestions(topArtists, newAlbums, endpoint, toArtist, toAlbum)
            else results()
        }
    }
}

/** Room the collapsed bar takes at the top: its height and the space around it. */
private val SEARCH_BAR_ROOM = 80.dp

/** At rest: the artists with the most songs, then the newest albums, three to a row. */
@Composable
private fun SearchBrowse(artists: List<LibraryArtist>, albums: List<LibraryAlbum>, endpoint: ServerEndpoint?,
    openArtist: (LibraryArtist) -> Unit, openAlbum: (LibraryAlbum) -> Unit, modifier: Modifier) {
    if (artists.isEmpty() && albums.isEmpty()) {
        Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Your collection, one search away.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 16.dp)) {
        if (artists.isNotEmpty()) {
            item(key = "artists-label", contentType = "label") { SectionHeading("Artists") }
            item(key = "artists", contentType = "artists") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(artists, key = { it.key }, contentType = { "artist" }) { artist ->
                        Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp))
                            .clickable(onClickLabel = "Open artist") { openArtist(artist) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            ArtistAvatar(artist, side = 80.dp, endpoint = endpoint)
                            Text(artistLabel(artist.name), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
                        }
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item(key = "albums-label", contentType = "label") { SectionHeading("Albums") }
            items(albums.chunked(3), key = { row -> "albums:" + row.first().key }, contentType = { "album-row" }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    row.forEach { album ->
                        AlbumTile(album, endpoint, subtitle = artistLabel(displayCredits(album.artist)),
                            modifier = Modifier.weight(1f)) { openAlbum(album) }
                    }
                    // A short last row keeps the tiles the same size as the rows above.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Before typing: a few artists and albums from the library to jump to, alternating. */
@Composable
private fun SearchSuggestions(artists: List<LibraryArtist>, albums: List<LibraryAlbum>, endpoint: ServerEndpoint?,
    openArtist: (LibraryArtist) -> Unit, openAlbum: (LibraryAlbum) -> Unit) {
    val suggestions = remember(artists, albums) { searchSuggestions(artists, albums) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (suggestions.isNotEmpty()) item(key = "label", contentType = "label") {
            Text("From your library", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp))
        }
        items(suggestions, key = { it.fold({ a -> a.key }, { a -> a.key }) }) { suggestion ->
            suggestion.fold({ artist ->
                ListItem(headlineContent = { Text(artistLabel(artist.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Artist") }, leadingContent = { ArtistAvatar(artist, side = 48.dp, endpoint = endpoint) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = "Open artist") { openArtist(artist) })
            }, { album ->
                ListItem(headlineContent = { Text(albumLabel(album.title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Album · ${artistLabel(displayCredits(album.artist))}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Artwork(albumArt(endpoint, album), Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                        .clickable(onClickLabel = "Open album") { openAlbum(album) })
            })
        }
    }
}

/** One suggestion before typing: an artist or an album. */
internal sealed interface SearchSuggestion {
    data class Artist(val artist: LibraryArtist) : SearchSuggestion
    data class Album(val album: LibraryAlbum) : SearchSuggestion
}

private inline fun <R> SearchSuggestion.fold(artist: (LibraryArtist) -> R, album: (LibraryAlbum) -> R): R = when (this) {
    is SearchSuggestion.Artist -> artist(this.artist)
    is SearchSuggestion.Album -> album(this.album)
}

/** Up to [each] artists and [each] albums, alternating from an artist, as the mockup's list does. */
internal fun searchSuggestions(artists: List<LibraryArtist>, albums: List<LibraryAlbum>, each: Int = 3): List<SearchSuggestion> {
    val a = artists.take(each).map { SearchSuggestion.Artist(it) }
    val b = albums.take(each).map { SearchSuggestion.Album(it) }
    return (0 until maxOf(a.size, b.size)).flatMap { listOfNotNull(a.getOrNull(it), b.getOrNull(it)) }
}

/** The browse at rest and the suggestions: the artists with the most songs, and the newest albums. */
internal fun topArtists(artists: List<LibraryArtist>, count: Int = 10): List<LibraryArtist> =
    artists.filter { it.name.isNotBlank() }.sortedByDescending { it.tracks.size }.take(count)

internal fun newestAlbums(albums: List<LibraryAlbum>, count: Int = 9): List<LibraryAlbum> =
    sortAlbums(albums.filter { it.title.isNotBlank() }, AlbumOrder.Added).take(count)

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
                leadingContent = { ArtistAvatar(artist, endpoint = endpoint) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Open artist") { openArtist(artist) },
            )
        }
    }
    if (albums.isNotEmpty()) {
        item(key = "search:albums", contentType = "label") { SectionHeading("Albums") }
        item(key = "search:album-row", contentType = "albums") {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(albums, key = { it.key }, contentType = { "album" }) { album ->
                    AlbumTile(album, endpoint, subtitle = artistLabel(displayCredits(album.artist))) { openAlbum(album) }
                }
            }
        }
    }
    item(key = "search:songs", contentType = "label") { SectionHeading("Songs") }
}
