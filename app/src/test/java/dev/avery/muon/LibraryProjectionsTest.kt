package dev.avery.muon

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Locale

/** #639: worker projections publish only for what they were made from, and stop when superseded. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryProjectionsTest {
    private fun track(id: Long, title: String = "Song $id", artist: String = "Artist") =
        TauonTrack(id, title, artist, "Album", 1000L, true, false)

    @Test fun anEqualReplacementSnapshotAnotherServerOrOrderNeverShowsAnOldProjection() {
        val tracks = listOf(track(1), track(2))
        val key = SongProjectionKey(LibrarySnapshot(tracks), "http://a", SongOrder.Added, Locale.ROOT)
        val made = Projection(key, sortSongs(tracks, SongOrder.Added))
        assertEquals(listOf(2L, 1L), made.current(key)!!.map { it.id })
        // The same songs reloaded are a new snapshot: an identity, not a comparison of contents.
        assertNull(made.current(key.copy(snapshot = LibrarySnapshot(tracks.toList()))))
        assertNull(made.current(key.copy(origin = "http://b")))
        assertNull(made.current(key.copy(order = SongOrder.Title)))
        assertNull(made.current(key.copy(locale = Locale.JAPANESE)))
        // A new wrapper around the same immutable list is the same effect/publication owner.
        assertSame(made.value, made.current(key.copy(snapshot = LibrarySnapshot(tracks))))
        // Equal groups made again belong to another grouping, so its orders are not reused either.
        val groups = ArtistGroups("http://a", key.snapshot, groupArtists(tracks))
        val sorted = Projection(ArtistProjectionKey(groups, ArtistOrder.Name, Locale.ROOT), groups.artists)
        val regrouped = ArtistGroups("http://a", key.snapshot, groups.artists)
        assertNull(sorted.current(ArtistProjectionKey(regrouped, ArtistOrder.Name, Locale.ROOT)))
    }

    @Test fun cpuHelpersStopInsideComparisonsFiltersAndNestedScans() {
        fun stopAfter(calls: Int): () -> Unit { var n = 0; return { if (++n > calls) throw CancellationException("superseded") } }
        val tracks = (1L..200L).map { track(it, title = "T${200 - it}") }
        // Recently added builds no keys: only the comparisons can observe the cancellation.
        assertThrows(CancellationException::class.java) { sortSongs(tracks, SongOrder.Added, checkpoint = stopAfter(10)) }
        assertThrows(CancellationException::class.java) { sortSongs(tracks, SongOrder.Title, checkpoint = stopAfter(10)) }
        // One album: only the scan of its songs for the newest one can reach the limit.
        val album = LibraryAlbum("album:1", "Album", "Artist", tracks)
        assertThrows(CancellationException::class.java) { sortAlbums(listOf(album), AlbumOrder.Added, checkpoint = stopAfter(10)) }
        assertThrows(CancellationException::class.java) { searchTracks(tracks, "t", stopAfter(10)) }
        val artists = tracks.map { LibraryArtist("artist:${it.id}", "Artist ${it.id}", listOf(it)) }
        assertThrows(CancellationException::class.java) { sortArtists(artists, ArtistOrder.Name, checkpoint = stopAfter(10)) }
        assertThrows(CancellationException::class.java) { searchArtists(artists, "artist", checkpoint = stopAfter(10)) }
        // Unchanged results when not cancelled: Added is newest first, untouched input.
        assertEquals(200L, sortSongs(tracks, SongOrder.Added).first().id)
        assertEquals(1L, tracks.first().id)
    }

    @Test fun queuedSortRunsOnItsWorkerAndCancellationPublishesOnlyTheReplacementOrder() {
        val scheduler = TestCoroutineScheduler()
        val worker = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val tracks = listOf(track(1, "Zulu"), track(2, "Alpha"))
        val old = SongProjectionKey(LibrarySnapshot(tracks), "http://a", SongOrder.Added, Locale.ROOT)
        val replacement = old.copy(order = SongOrder.Title)
        var published: Projection<SongProjectionKey, List<TauonTrack>>? = null
        try {
            val superseded = scope.launch { published = projectSongs(old, worker) }
            assertTrue(superseded.isActive)
            assertNull(published) // The real sort is queued on the worker, not run by the caller.
            superseded.cancel()
            scope.launch { published = projectSongs(replacement, worker) }
            assertNull(published)
            scheduler.advanceUntilIdle()
            assertTrue(superseded.isCancelled)
            assertEquals(replacement, published?.owner)
            assertNull(published.current(old))
            assertEquals(listOf(2L, 1L), published.current(replacement)!!.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun rapidQueriesPublishOnlyTheLatestAndLateSourcesKeepTheCompletedQuery() = runTest {
        val tracks = listOf(track(1, "Alpha"), track(2, "Beta", artist = "Alpha band"), track(3, "Gamma"))
        val worker = StandardTestDispatcher(testScheduler)
        val finished = mutableListOf<String>()
        var state = SearchResults()
        fun TestScope.start(query: String, artists: ArtistGroups? = null,
            albums: Projection<AlbumProjectionKey, List<LibraryAlbum>>? = null): Job = launch {
            state = runSearch(state, query, tracks, artists, albums, { state = it }, worker).also { finished += it.completed }
        }
        // A keystroke within the debounce restarts the search, as produceState does on a new key.
        val first = start("al")
        advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        first.cancel()
        start("alp")
        advanceUntilIdle()
        assertEquals(listOf("alp"), finished)
        assertEquals(listOf(1L, 2L), state.tracks.map { it.id })

        // Groups and an album order arrive later: matched at once, with the same completed query, so
        // the saved results list is not reset. Albums keep the chosen order between equal matches.
        val snapshot = LibrarySnapshot(tracks)
        val groups = ArtistGroups("o", snapshot, groupArtists(tracks))
        val two = LibraryAlbum("album:2", "Alp two", "X", emptyList())
        val one = LibraryAlbum("album:1", "Alp one", "X", emptyList())
        val chosen = Projection(AlbumProjectionKey(AlbumGroups("o", snapshot, listOf(one, two)), AlbumOrder.Title, Locale.ROOT),
            listOf(two, one))
        val before = testScheduler.currentTime
        start("alp ", groups, chosen)
        advanceUntilIdle()
        assertEquals(before, testScheduler.currentTime)
        assertEquals(listOf("alp", "alp"), finished)
        assertSame(groups, state.artistSource)
        assertSame(chosen, state.albumSource)
        assertEquals(listOf("Alpha band"), state.artists.map { it.name })
        assertEquals(listOf("album:2", "album:1"), state.albums.map { it.key })
    }
}
