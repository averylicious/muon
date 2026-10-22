package dev.avery.muon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/**
 * One worker-made view of the library (#639) and exactly what it was made from. It is shown only while
 * [owner] still equals what the screen would ask for now, so a result finished for an earlier snapshot,
 * server, order or locale is hidden in the same composition the request changes, before any effect runs.
 * Owners compare snapshots and groups by identity, never the lists they hold.
 */
internal class Projection<K, T>(val owner: K, val value: T)

internal fun <K, T> Projection<K, T>?.current(owner: K): T? = this?.takeIf { it.owner == owner }?.value

/** The Songs order for one snapshot; the origin keeps a server change from reusing it. */
internal data class SongProjectionKey(val snapshot: LibrarySnapshot, val origin: String?,
    val order: SongOrder, val locale: Locale)

/** An order of one grouping: [groups] carries its snapshot and server, compared by identity. */
internal data class ArtistProjectionKey(val groups: ArtistGroups, val order: ArtistOrder, val locale: Locale)
internal data class AlbumProjectionKey(val groups: AlbumGroups, val order: AlbumOrder, val locale: Locale)

/** Search's browse at rest, made once by the grouping worker for [groups]. */
internal class GroupBrowse(val groups: ArtistGroups, val artists: List<LibraryArtist>, val albums: List<LibraryAlbum>)

/** Sorts on [worker], with a collator of its own for [SongProjectionKey.locale]. */
internal suspend fun projectSongs(key: SongProjectionKey, worker: CoroutineContext = Dispatchers.Default) =
    withContext(worker) {
        val job = currentCoroutineContext()
        Projection(key, sortSongs(key.snapshot.tracks, key.order, libraryCollator(key.locale)) { job.ensureActive() })
    }

internal suspend fun projectArtists(key: ArtistProjectionKey, worker: CoroutineContext = Dispatchers.Default) =
    withContext(worker) {
        val job = currentCoroutineContext()
        Projection(key, sortArtists(key.groups.artists, key.order, libraryCollator(key.locale)) { job.ensureActive() })
    }

internal suspend fun projectAlbums(key: AlbumProjectionKey, worker: CoroutineContext = Dispatchers.Default) =
    withContext(worker) {
        val job = currentCoroutineContext()
        Projection(key, sortAlbums(key.groups.albums, key.order, libraryCollator(key.locale)) { job.ensureActive() })
    }

/**
 * One search of the songs, the raw artist groups (their order breaks ties) and the albums in the chosen
 * order. A new query waits out [SEARCH_DEBOUNCE_MS], reporting [searching] meanwhile with the previous
 * results. When only the artist/album sources changed, for example grouping finished or the album order
 * was changed, the finished songs are reused at once and [SearchResults.completed] stays the same, so
 * the saved results list keeps its place. The caller restarts this for a new snapshot.
 */
internal suspend fun runSearch(previous: SearchResults, query: String, tracks: List<TauonTrack>,
    artists: ArtistGroups?, albums: Projection<AlbumProjectionKey, List<LibraryAlbum>>?,
    searching: (SearchResults) -> Unit, worker: CoroutineContext = Dispatchers.Default): SearchResults {
    val needle = query.trim()
    if (needle.isEmpty()) return SearchResults()
    val again = previous.completed == needle
    if (!again) {
        searching(previous.copy(searching = true))
        delay(SEARCH_DEBOUNCE_MS)
    }
    return withContext(worker) {
        val job = currentCoroutineContext()
        val check = { job.ensureActive() }
        SearchResults(if (again) previous.tracks else searchTracks(tracks, needle, check),
            searching = false, completed = needle,
            artists = artists?.let { searchArtists(it.artists, needle, checkpoint = check) }.orEmpty(), artistSource = artists,
            albums = albums?.let { searchAlbums(it.value, needle, checkpoint = check) }.orEmpty(), albumSource = albums)
    }
}
