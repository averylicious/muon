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

    private val tracks = listOf(track(1, "A"), track(2, "B"))
    private val snapshot = LibrarySnapshot(tracks)
    private fun grouped(origin: String, snapshot: LibrarySnapshot) =
        ArtistGroups(origin, snapshot, groupArtists(snapshot.tracks))

    /** What the app derives from the model's playlists, as `LibraryModel.allTracks` does. */
    private fun snapshotOf(byPlaylist: Map<String, List<TauonTrack>>) =
        LibrarySnapshot(byPlaylist.values.flatten().distinctBy { it.id })

    /**
     * The grouping effect as Compose runs it: it restarts when any key is unequal (`!=`) to the one
     * it last ran with, and a restarted grouping publishes its groups when it finishes.
     */
    private class GroupingEffect {
        var groups: ArtistGroups? = null
        private var keys: Pair<LibrarySnapshot, String?>? = null
        var pending: Pair<LibrarySnapshot, String?>? = null

        fun compose(snapshot: LibrarySnapshot, origin: String?): List<LibraryArtist>? {
            val now = snapshot to origin
            if (now != keys) { keys = now; pending = now }
            return currentArtists(groups, origin, snapshot)
        }

        fun finish() {
            val (snapshot, origin) = pending ?: return
            pending = null
            groups = origin?.let { ArtistGroups(it, snapshot, groupArtists(snapshot.tracks)) }
        }

        /** Nothing shown means a grouping is on its way; it can never wait forever. */
        fun settles(snapshot: LibrarySnapshot, origin: String?): List<LibraryArtist>? {
            val shown = compose(snapshot, origin)
            if (shown == null && origin != null) assertNotNull("no grouping running", pending)
            finish()
            return compose(snapshot, origin)
        }
    }

    @Test fun theEffectRestartsExactlyWhenTheGuardStopsAcceptingItsGroups() {
        val lists = listOf(tracks, tracks.toList(), listOf(track(9, "A")), emptyList())
        val snapshots = lists.map(::LibrarySnapshot) + snapshot
        snapshots.forEach { a ->
            snapshots.forEach { b ->
                // Same key to the effect exactly when the guard keeps the groups.
                assertEquals(a == b, currentArtists(grouped("http://a", a), "http://a", b) != null)
                if (a == b) assertEquals(a.hashCode(), b.hashCode())
            }
        }
        assertEquals(snapshot, snapshot)
        assertNotEquals(LibrarySnapshot(tracks), LibrarySnapshot(tracks.toList()))
    }

    @Test fun aReshapedLibraryWithTheSameSongsRegroupsInsteadOfLoadingForever() {
        val effect = GroupingEffect()
        val before = snapshotOf(mapOf("1" to tracks, "2" to emptyList()))
        assertEquals(listOf("A", "B"), effect.settles(before, "http://a")!!.map { it.name })
        // Playlists split differently and the empty one gone: equal songs, a new snapshot.
        val after = snapshotOf(mapOf("1" to tracks.take(1), "3" to tracks.drop(1)))
        assertEquals(before.tracks, after.tracks)
        assertNull("the old groups are not offered", effect.compose(after, "http://a"))
        assertNotNull("so the effect must have restarted", effect.pending)
        effect.finish()
        assertEquals(listOf("A", "B"), effect.compose(after, "http://a")!!.map { it.name })
    }

    @Test fun anUnchangedSnapshotKeepsItsGroupsWithoutRegrouping() {
        val effect = GroupingEffect()
        assertNotNull(effect.settles(snapshot, "http://a"))
        assertNotNull(effect.compose(snapshot, "http://a"))
        assertNull(effect.pending)
    }

    @Test fun groupsForTheCurrentSnapshotAndServerAreShown() {
        val groups = grouped("http://a:7814", snapshot)
        assertEquals(listOf("A", "B"), currentArtists(groups, "http://a:7814", snapshot)!!.map { it.name })
        assertNull(currentArtists(null, "http://a:7814", snapshot))
    }

    @Test fun groupsForAnotherServerAreNeverShown() {
        val effect = GroupingEffect()
        assertNotNull(effect.settles(snapshot, "http://a:7814"))
        assertNull(currentArtists(effect.groups, "http://b:7814", snapshot))
        // The other server's own grouping then arrives for it.
        assertNotNull(effect.settles(snapshot, "http://b:7814"))
    }

    @Test fun aSameServerRefreshHidesTheOldGroupsUntilItsOwnArrive() {
        val effect = GroupingEffect()
        effect.settles(snapshot, "http://a:7814")
        // The refresh removed B and renumbered A; the old groups would still offer both.
        val refreshed = LibrarySnapshot(listOf(track(9, "A")))
        assertNull(effect.compose(refreshed, "http://a:7814"))
        effect.finish()
        assertEquals(listOf(9L), effect.compose(refreshed, "http://a:7814")!!.single().tracks.map { it.id })
    }

    @Test fun reconnectingToTheSameServerNeverOffersThePreviousLibrary() {
        val effect = GroupingEffect()
        effect.settles(snapshot, "http://a:7814")
        // Disconnected: no server, a fresh empty library, and the groups are dropped.
        assertNull(effect.settles(LibrarySnapshot(emptyList()), null))
        assertNull(effect.groups)
        val reloaded = LibrarySnapshot(listOf(track(1, "A"), track(2, "B")))
        val shown = effect.compose(reloaded, "http://a:7814")
        assertNull(shown)
        // A saved artist waits for the new grouping rather than opening on the old one or being lost.
        assertEquals(StoredSelection.Wait, storedArtist("http://a:7814", "artist:b", "http://a:7814", shown))
        effect.finish()
        val regrouped = effect.compose(reloaded, "http://a:7814")
        assertEquals(StoredSelection.Open, storedArtist("http://a:7814", "artist:b", "http://a:7814", regrouped))
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

    @Test fun coverTracksAreOnePerAlbumNewestFirst() {
        fun song(id: Long, album: String) = TauonTrack(id, "Song $id", "Zedd", album, 1000, true, false)
        val zedd = LibraryArtist("artist:zedd", "Zedd", listOf(song(1, "Clarity"), song(2, "Clarity"),
            song(5, "True Colors"), song(3, "Stay"), song(9, ""), song(8, "")))
        assertEquals(listOf(9L, 5L, 3L, 2L), artistCoverTracks(zedd, 4).map { it.id })
        assertEquals(listOf(9L), artistCoverTracks(zedd, 1).map { it.id })
    }
}
