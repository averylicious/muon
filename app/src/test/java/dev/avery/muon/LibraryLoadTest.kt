package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LibraryLoadTest {
    private val a = TauonPlaylist("a", "A", 1)
    private val b = TauonPlaylist("b", "B", 1)
    private val c = TauonPlaylist("c", "C", 1)
    private fun song(id: Long) = listOf(TauonTrack(id, "Song $id", "", "", 1, true, false))

    @Test fun everythingLoadedIsEverythingShown() {
        val load = combineLoad(listOf(a, b), mapOf("a" to song(1), "b" to song(2)), null)
        assertEquals(listOf(a, b), load.playlists)
        assertEquals(0, load.failed)
    }

    @Test fun aFailedPlaylistNoLongerThrowsAwayTheOthers() {
        val load = combineLoad(listOf(a, b, c), mapOf("a" to song(1), "c" to song(3)), null)
        assertEquals(listOf(a, c), load.playlists)
        assertEquals(setOf("a", "c"), load.tracks.keys)
        assertEquals(1, load.failed)
    }

    @Test fun aFailedPlaylistKeepsWhatTheLastLoadHad() {
        val load = combineLoad(listOf(a, b), mapOf("a" to song(1)), mapOf("a" to song(9), "b" to song(2)))
        assertEquals(listOf(a, b), load.playlists)
        assertEquals(song(1), load.tracks["a"])
        assertEquals(song(2), load.tracks["b"])
        assertEquals(1, load.failed)
    }

    @Test fun tauonsOrderIsKept() {
        val load = combineLoad(listOf(c, a), mapOf("a" to song(1), "c" to song(3)), null)
        assertEquals(listOf("c", "a"), load.tracks.keys.toList())
    }

    @Test fun theNoteSaysHowManyOfHowMany() {
        assertEquals("1 of 12 playlists didn't load. Retry to try again.", partialLoadMessage(1, 12))
    }
    @Test fun uniqueProjectionKeepsFirstObjectAndOrderAcrossEmptyDuplicatesAndLaterRefresh() {
        val first = song(7).single()
        val conflicting = first.copy(title = "Later conflicting title", playable = false)
        val last = song(9).single()
        val old = linkedMapOf("empty" to emptyList(), "first" to listOf(first, first),
            "other" to listOf(conflicting, last), "trailing" to emptyList())
        val projected = libraryTracks(old)
        assertEquals(listOf(7L, 9L), projected.map { it.id })
        assertSame(first, projected[0]); assertSame(last, projected[1])
        assertEquals(2, old.getValue("first").size)
        val fresh = first.copy(title = "Fresh title")
        val next = libraryTracks(linkedMapOf("fresh" to listOf(fresh), "fallback" to old.getValue("other")))
        assertSame(fresh, next[0]); assertSame(last, next[1]); assertSame(first, projected[0])
        assertTrue(libraryTracks(emptyMap()).isEmpty())
        assertTrue(libraryTracks(mapOf("empty" to emptyList())).isEmpty())
    }

}
