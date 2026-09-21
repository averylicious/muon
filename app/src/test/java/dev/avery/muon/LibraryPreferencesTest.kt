package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryPreferencesTest {
    private val music = TauonPlaylist(id = "3", name = "Music", count = 962)
    private val emptied = TauonPlaylist(id = "7", name = "Later", count = 0)

    @Test fun songsIsWhereTheLibraryOpensUntilSomeoneChoosesOtherwise() {
        assertEquals(LibraryView.Songs, libraryViewFrom(null))
        assertEquals(LibraryView.Songs, libraryViewFrom(""))
        assertEquals(LibraryView.Songs, libraryViewFrom("Albums"))
    }

    @Test fun aStoredChoiceComesBack() {
        assertEquals(LibraryView.Playlists, libraryViewFrom("Playlists"))
        assertEquals(LibraryView.Songs, libraryViewFrom("Songs"))
    }

    @Test fun aPlaylistWithMusicInItOpens() {
        assertEquals(music, openPlaylist("3", listOf(emptied, music)))
    }

    /** A refresh removed it, emptied it, or this is a different server reusing the identifier. */
    @Test fun anIdentifierThatNoLongerNamesMusicOpensNothing() {
        assertNull(openPlaylist("3", listOf(emptied)))
        assertNull(openPlaylist("7", listOf(emptied, music)))
        assertNull(openPlaylist("3", emptyList()))
    }

    @Test fun nothingOpenStaysNothingOpen() {
        assertNull(openPlaylist(null, listOf(music)))
    }
}
