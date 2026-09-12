package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSearchTest {
    private fun track(id: Long, title: String, artist: String, album: String) =
        TauonTrack(id, title, artist, album, 1000, true, false)

    private val library = listOf(
        track(1, "Blue Line", "Ana Rivers", "Transit"),
        track(2, "Night Bus", "Kilo Park", "Blue Hours"),
        track(3, "Static", "blue collar", "Noise"),
    )

    @Test fun matchesTitleArtistAndAlbumCaseInsensitively() {
        assertEquals(listOf(1L, 2L, 3L), searchTracks(library, "blue").map { it.id })
        assertEquals(listOf(2L), searchTracks(library, "KILO").map { it.id })
        assertEquals(listOf(1L), searchTracks(library, "Transit").map { it.id })
    }

    @Test fun blankQueryReturnsNothingAndSurroundingSpaceIsIgnored() {
        assertEquals(emptyList<TauonTrack>(), searchTracks(library, ""))
        assertEquals(emptyList<TauonTrack>(), searchTracks(library, "   "))
        assertEquals(listOf(2L), searchTracks(library, "  night  ").map { it.id })
    }

    @Test fun emptyStateDistinguishesNotTypedSearchingAndNoMatches() {
        assertEquals("Your collection, one search away.", searchEmptyText("", false, ""))
        assertEquals("Your collection, one search away.", searchEmptyText("   ", false, "blue"))
        assertEquals("Searching\u2026", searchEmptyText("blu", true, ""))
        // A finished query must not be reported as missing while the next one is still running.
        assertEquals("Searching\u2026", searchEmptyText("blue", true, "blu"))
        assertEquals("Searching\u2026", searchEmptyText("blue", false, ""))
        assertEquals("Nothing matches \u201cblue\u201d.", searchEmptyText("blue", false, "blue"))
    }

    @Test fun unmatchedQueryReturnsNothing() {
        assertEquals(emptyList<TauonTrack>(), searchTracks(library, "orchestra"))
    }
}
