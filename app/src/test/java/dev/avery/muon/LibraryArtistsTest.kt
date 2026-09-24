package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LibraryArtistsTest {
    private fun track(id: Long, artist: String) = TauonTrack(id, "Track $id", artist, "Album", 1000, true, false)

    private fun artist(name: String, vararg ids: Long) =
        LibraryArtist("artist:${name.lowercase()}", name, ids.map { track(it, name) })

    @Test fun initialsAreTheFirstLetterOfEachOfTheFirstTwoWords() {
        assertEquals("TW", artistInitials("The Weeknd"))
        assertEquals("AB", artistInitials("aphex bjork cocteau"))
        assertEquals("A", artistInitials("aexcit"))
    }

    @Test fun digitsAndPunctuationAreKeptAsTheyAre() {
        assertEquals("5S", artistInitials("5 Seconds of Summer"))
        assertEquals("1F", artistInitials("12 Feet Deep"))
        assertEquals("(C", artistInitials("(hed) Chaos"))
    }

    @Test fun anyRunOfWhitespaceSeparatesWords() {
        assertEquals("MA", artistInitials("  massive \t\n attack  "))
        assertEquals("MA", artistInitials("\r\nmassive\u000Battack"))
        // No-break, figure, narrow and ideographic spaces separate words too, as they do for isBlank.
        assertEquals("SR", artistInitials("Sigur\u00A0Rós"))
        assertEquals("AB", artistInitials("a\u2007b"))
        assertEquals("AB", artistInitials("a\u202Fb"))
        assertEquals("坂龍", artistInitials("坂本\u3000龍一"))
        assertEquals("", artistInitials("\u00A0\u3000"))
    }

    @Test fun eachInitialIsExactlyOneCodePoint() {
        assertEquals("ß", artistInitials("ßtraße"))
        assertEquals("ÉA", artistInitials("élan aa"))
    }

    @Test fun initialsTakeWholeCodePointsNotHalfASurrogatePair() {
        assertEquals("𝄞C", artistInitials("𝄞 Clef"))
        // Deseret has case, outside the Basic Multilingual Plane.
        assertEquals("𐐀", artistInitials("𐐨𐑉"))
        assertEquals("坂龍", artistInitials("坂本 龍一"))
    }

    @Test fun uppercasingDoesNotDependOnTheDeviceLocale() {
        assertEquals("I", artistInitials("istanbul"))
    }

    @Test fun aBlankNameHasNoInitialsAndAnUnknownLabel() {
        listOf("", "   ", "\t").forEach {
            assertEquals("", artistInitials(it))
            assertEquals("Unknown artist", artistLabel(it))
        }
        assertEquals("The Weeknd", artistLabel("The Weeknd"))
    }

    @Test fun songCountsAgreeInNumber() {
        assertEquals("0 songs", artistSongCount(0))
        assertEquals("1 song", artistSongCount(1))
        assertEquals("2 songs", artistSongCount(2))
    }

    @Test fun anArtistKeepsItsToneAndEveryToneIsOneOfThree() {
        val keys = (0 until 200).map { "artist:name $it" }
        keys.forEach {
            assertEquals(artistTone(it), artistTone(it))
            assertTrue(artistTone(it) in 0..2)
        }
        assertEquals(setOf(0, 1, 2), keys.map(::artistTone).toSet())
    }

    private val snapshot = listOf(track(1, "A"), track(2, "B"))
    private fun grouped(origin: String, tracks: List<TauonTrack>) = ArtistGroups(origin, tracks, groupArtists(tracks))

    @Test fun groupsForTheCurrentSnapshotAndServerAreShown() {
        val groups = grouped("http://a:7814", snapshot)
        assertEquals(listOf("A", "B"), currentArtists(groups, "http://a:7814", snapshot)!!.map { it.name })
        assertNull(currentArtists(null, "http://a:7814", snapshot))
    }

    @Test fun groupsForAnotherServerAreNeverShown() {
        val groups = grouped("http://a:7814", snapshot)
        assertNull(currentArtists(groups, "http://b:7814", snapshot))
    }

    @Test fun aSameServerRefreshHidesTheOldGroupsUntilItsOwnArrive() {
        val groups = grouped("http://a:7814", snapshot)
        // The refresh removed B and renumbered A; the old groups would still offer both.
        val refreshed = listOf(track(9, "A"))
        assertNull(currentArtists(groups, "http://a:7814", refreshed))
        // Equal contents in a new list are still a new snapshot.
        assertNull(currentArtists(groups, "http://a:7814", snapshot.toList()))
        val regrouped = grouped("http://a:7814", refreshed)
        assertEquals(listOf(9L), currentArtists(regrouped, "http://a:7814", refreshed)!!.single().tracks.map { it.id })
    }

    @Test fun reconnectingToTheSameServerNeverOffersThePreviousLibrary() {
        val groups = grouped("http://a:7814", snapshot)
        // Disconnected: no server, and the model's library is a fresh empty list.
        assertNull(currentArtists(groups, null, emptyList()))
        val reloaded = listOf(track(1, "A"), track(2, "B"))
        assertNull(currentArtists(groups, "http://a:7814", reloaded))
        // A saved artist waits for the new grouping rather than opening on the old one or being lost.
        assertEquals(StoredSelection.Wait, storedArtist("http://a:7814", "artist:b", "http://a:7814",
            currentArtists(groups, "http://a:7814", reloaded)))
    }

    @Test fun nothingSavedMeansNothingToOpen() {
        val artists = listOf(artist("A", 1))
        assertEquals(StoredSelection.None, storedArtist(null, null, "http://a", artists))
        assertEquals(StoredSelection.None, storedArtist("http://a", null, "http://a", artists))
        assertEquals(StoredSelection.None, storedArtist(null, "artist:a", "http://a", artists))
    }

    @Test fun aSavedArtistWaitsForTheServerAndItsGrouping() {
        assertEquals(StoredSelection.Wait, storedArtist("http://a", "artist:a", null, null))
        assertEquals(StoredSelection.Wait, storedArtist("http://a", "artist:a", "http://a", null))
    }

    @Test fun aSavedArtistOpensOnlyOnItsOwnServerWhileItStillExists() {
        val artists = listOf(artist("A", 1), artist("B", 2))
        assertEquals(StoredSelection.Open, storedArtist("http://a", "artist:b", "http://a", artists))
        assertEquals(StoredSelection.Discard, storedArtist("http://a", "artist:b", "http://b", artists))
        // Another server is discarded even before its grouping arrives.
        assertEquals(StoredSelection.Discard, storedArtist("http://a", "artist:b", "http://b", null))
    }

    @Test fun aWaitingArtistKeepsItsPageAndBackClosesItForGood() {
        val waiting = storedArtist("http://a", "artist:b", "http://a", null)
        assertTrue(artistPageShown(waiting, connected = true))
        assertEquals(BackTarget.Playlist, backTarget(connected = true, lyricsShown = false, overlayOpen = false,
            onLibraryTab = true, playlistOpen = artistPageShown(waiting, connected = true)))
        // Back cleared the selection; the grouping that then arrives does not bring the page back.
        val arrived = groupArtists(listOf(track(1, "A"), track(2, "B")))
        val afterBack = storedArtist(null, null, "http://a", arrived)
        assertEquals(StoredSelection.None, afterBack)
        assertFalse(artistPageShown(afterBack, connected = true))
    }

    @Test fun onlyAnOpenOrWaitingArtistShowsItsPageAndNeverWhileDisconnected() {
        assertTrue(artistPageShown(StoredSelection.Open, connected = true))
        assertFalse(artistPageShown(StoredSelection.None, connected = true))
        assertFalse(artistPageShown(StoredSelection.Discard, connected = true))
        assertFalse(artistPageShown(StoredSelection.Wait, connected = false))
        assertFalse(artistPageShown(StoredSelection.Open, connected = false))
    }

    @Test fun aRefreshThatRemovesTheArtistForgetsIt() {
        val before = listOf(artist("A", 1), artist("B", 2))
        assertEquals(StoredSelection.Open, storedArtist("http://a", "artist:b", "http://a", before))
        val after = listOf(artist("A", 1))
        assertEquals(StoredSelection.Discard, storedArtist("http://a", "artist:b", "http://a", after))
        assertEquals(StoredSelection.Discard, storedArtist("http://a", "artist:b", "http://a", emptyList()))
    }

    @Test fun groupedKeysAreWhatASelectionIsJudgedAgainst() {
        val grouped = groupArtists(listOf(track(1, "Air; Beck"), track(2, "beck")))
        val beck = grouped.single { it.name == "Beck" }
        assertEquals(StoredSelection.Open, storedArtist("http://a", beck.key, "http://a", grouped))
        assertEquals(2, beck.tracks.size)
        assertEquals("2 songs", artistSongCount(beck.tracks.size))
    }
}
