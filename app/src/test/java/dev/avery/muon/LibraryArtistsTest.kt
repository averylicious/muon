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

    @Test fun artistsGroupedForOneServerAreNeverShownForAnother() {
        val groups = ArtistGroups("http://a:7814", listOf(artist("A", 1)))
        assertEquals(groups.artists, currentArtists(groups, "http://a:7814"))
        assertNull(currentArtists(groups, "http://b:7814"))
        assertNull(currentArtists(groups, null))
        assertNull(currentArtists(null, "http://a:7814"))
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
