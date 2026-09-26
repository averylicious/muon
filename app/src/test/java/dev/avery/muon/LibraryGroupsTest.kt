package dev.avery.muon

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class LibraryGroupsTest {
    private fun track(id: Long, artist: String = "Performer", album: String = "Album",
        albumArtist: String = artist, number: String = "", playable: Boolean = true) =
        TauonTrack(id, "Track $id", artist, album, 1000, playable, false, albumArtist, number)

    @Test fun identicalAlbumTitlesByDifferentArtistsStaySeparate() {
        val albums = groupAlbums(listOf(track(1, "A"), track(2, "B")))
        assertEquals(2, albums.size)
        assertNotEquals(albums[0].key, albums[1].key)
    }

    @Test fun compilationGroupsByAlbumArtistButArtistViewKeepsPerformers() {
        val tracks = listOf(track(1, "A", albumArtist = "Various Artists"),
            track(2, "B", albumArtist = "Various Artists"))
        assertEquals(listOf(1L, 2L), groupAlbums(tracks).single().tracks.map { it.id })
        assertEquals(listOf("A", "B"), groupArtists(tracks).map { it.name })
    }

    @Test fun identityIgnoresCaseAndOuterWhitespaceButKeepsFirstDisplaySpelling() {
        val albums = groupAlbums(listOf(track(1, " Singer ", " Record "), track(2, "SINGER", "RECORD")))
        assertEquals(1, albums.size)
        assertEquals("Record", albums.single().title)
        assertEquals("Singer", albums.single().artist)
    }

    @Test fun keysDoNotDependOnInputOrderOrAmbiguousSeparators() {
        val a = track(1, "c", "a:b")
        val b = track(2, "b:c", "a")
        val groups = groupAlbums(listOf(a, b))
        assertNotEquals(groups[0].key, groups[1].key)
        assertEquals(groups.map { it.key }.toSet(), groupAlbums(listOf(b, a)).map { it.key }.toSet())
    }

    @Test fun groupingDoesNotSortTrackNumbersOrDiscardUnplayableTracks() {
        val tracks = listOf(track(3, number = "B2"), track(1, number = "1", playable = false),
            track(2, number = "1"))
        assertEquals(tracks, groupAlbums(tracks).single().tracks)
    }

    @Test fun repeatedTracksAcrossPlaylistsKeepFirstOccurrence() {
        val first = track(1)
        val later = track(1, "Changed artist", "Changed album")
        assertEquals(listOf(first), groupAlbums(listOf(first, later)).single().tracks)
        assertEquals(listOf(first), groupArtists(listOf(first, later)).single().tracks)
    }

    @Test fun artistCreditsSplitOnlyOnSemicolonsAndDeduplicateWithinATrack() {
        val t = track(1, " A ; a ; ; B/C & D, E ")
        val artists = groupArtists(listOf(t))
        assertEquals(listOf("A", "B/C & D, E"), artists.map { it.name })
        assertTrue(artists.all { it.tracks == listOf(t) })
    }

    @Test fun artistGroupsPreserveFirstAppearanceAndTrackOrder() {
        val artists = groupArtists(listOf(track(3, "B; A"), track(1, "a"), track(2, "B")))
        assertEquals(listOf("B", "A"), artists.map { it.name })
        assertEquals(listOf(3L, 2L), artists[0].tracks.map { it.id })
        assertEquals(listOf(3L, 1L), artists[1].tracks.map { it.id })
    }

    @Test fun emptyTagsStayUnknownAndBlankAlbumArtistUsesTrackArtist() {
        assertEquals("", groupArtists(listOf(track(1, " ; ; "))).single().name)
        val albums = groupAlbums(listOf(track(1, "A", "", ""), track(2, "B", "", "")))
        assertEquals(listOf("", ""), albums.map { it.title })
        assertEquals(listOf("A", "B"), albums.map { it.artist })
    }

    @Test fun identityIsIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            val tracks = listOf(track(1, "INDIGO", "I"), track(2, "indigo", "i"))
            val before = groupAlbums(tracks).single().key
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(before, groupAlbums(tracks).single().key)
            assertEquals(1, groupArtists(tracks).size)
        } finally { Locale.setDefault(original) }
    }

    @Test fun emptyLibraryHasNoGroups() {
        assertTrue(groupAlbums(emptyList()).isEmpty())
        assertTrue(groupArtists(emptyList()).isEmpty())
    }
}
