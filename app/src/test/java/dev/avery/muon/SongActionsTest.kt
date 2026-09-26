package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class SongActionsTest {
    private val hold = TauonTrack(1, "Hold On", "ILLENIUM; Georgia Ku", "ASCEND", 235_000, true, false, albumArtist = "ILLENIUM")
    private val other = TauonTrack(2, "Take You Down", "ILLENIUM", "ASCEND", 221_000, true, false, albumArtist = "ILLENIUM")
    private val loose = TauonTrack(3, "Demo", "", "", 100_000, true, false)
    private val library = listOf(hold, other, loose)

    @Test fun goToAlbumFindsTheSongsOwnAlbum() {
        val albums = groupAlbums(library)
        assertEquals("ASCEND", songAlbum(hold, albums)?.title)
        assertEquals(songAlbum(hold, albums), songAlbum(other, albums))
    }

    @Test fun anUntaggedSongHasNoAlbumOrArtistToGoTo() {
        assertNull(songAlbum(loose, groupAlbums(library)))
        assertEquals(emptyList<LibraryArtist>(), songArtists(loose, groupArtists(library)))
    }

    @Test fun everyCreditedArtistIsOfferedInCreditOrder() {
        val artists = groupArtists(library)
        assertEquals(listOf("ILLENIUM", "Georgia Ku"), songArtists(hold, artists).map { it.name })
        assertEquals(listOf("ILLENIUM"), songArtists(other, artists).map { it.name })
    }

    @Test fun nothingIsOfferedWhileTheLibraryIsStillBeingGrouped() {
        assertNull(songAlbum(hold, null))
        assertEquals(emptyList<LibraryArtist>(), songArtists(hold, null))
    }

    @Test fun theConfirmationUsesTheActionsOwnWords() {
        assertEquals("“Hold On” will play next", queuedMessage("Hold On", next = true))
        assertEquals("“Hold On” added to the queue", queuedMessage("Hold On", next = false))
        assertEquals("“Untitled” will play next", queuedMessage(" ", next = true))
    }
}
