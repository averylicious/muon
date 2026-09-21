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

    private val here = "http://10.0.0.2:7814"
    private val elsewhere = "http://10.0.0.9:7814"
    private val library = listOf(music, emptied)

    /** Rotation: the same server, the same library, so the detail comes back. */
    @Test fun aSelectionMadeOnThisServerReopens() {
        assertEquals(StoredSelection.Open,
            storedSelection(here, "3", here, library, libraryLoaded = true))
    }

    /**
     * Reopened from scratch: the selection is saved but the library has not arrived. Waiting
     * rather than discarding is what keeps the choice alive across process death.
     */
    @Test fun aSelectionWaitsForALibraryToJudgeItAgainst() {
        assertEquals(StoredSelection.Wait,
            storedSelection(here, "3", here, emptyList(), libraryLoaded = false))
        assertEquals(StoredSelection.Wait,
            storedSelection(here, "3", origin = null, playlists = emptyList(), libraryLoaded = false))
    }

    /** Another server may well have a playlist "3"; it is not this one. */
    @Test fun aSelectionNeverCrossesServers() {
        assertEquals(StoredSelection.Discard,
            storedSelection(here, "3", elsewhere, library, libraryLoaded = true))
    }

    @Test fun aRemovedOrEmptiedPlaylistIsForgotten() {
        assertEquals(StoredSelection.Discard,
            storedSelection(here, "3", here, listOf(emptied), libraryLoaded = true))
        assertEquals(StoredSelection.Discard,
            storedSelection(here, "7", here, library, libraryLoaded = true))
    }

    /**
     * Forgetting is what stops a reappearance from reopening the detail: once discarded there is
     * no identifier left for a later refresh to match.
     */
    @Test fun aReappearingPlaylistDoesNotReopenItself() {
        assertEquals(StoredSelection.Discard,
            storedSelection(here, "3", here, listOf(emptied), libraryLoaded = true))
        assertEquals(StoredSelection.None,
            storedSelection(savedOrigin = null, savedId = null, origin = here,
                playlists = library, libraryLoaded = true))
    }

    @Test fun nothingSavedIsNothingToDecide() {
        assertEquals(StoredSelection.None,
            storedSelection(here, savedId = null, origin = here, playlists = library, libraryLoaded = true))
        assertEquals(StoredSelection.None,
            storedSelection(savedOrigin = null, savedId = "3", origin = here,
                playlists = library, libraryLoaded = true))
    }
}
