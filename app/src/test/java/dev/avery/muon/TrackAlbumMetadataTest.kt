package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackAlbumMetadataTest {
    @Test fun compilationAlbumKeepsAlbumArtistSeparateFromTrackCredit() {
        assertEquals("Various Artists", albumArtistTag(" Various Artists ", "Guest Artist"))
    }

    @Test fun missingBlankOrWrongTypeAlbumArtistFallsBackToTrackArtist() {
        // Any() also covers an opaque JSON-null sentinel without depending on Android's JSON API.
        listOf(null, "", " \n\t", 12, false, listOf("Artist"), mapOf("name" to "Artist"), Any())
            .forEach { assertEquals("Track Artist", albumArtistTag(it, "Track Artist")) }
        assertEquals("", albumArtistTag(null, ""))
    }

    @Test fun unicodeAndMultiArtistCreditsAreNotSplitOrCaseFolded() {
        val credit = "Björk; 宇多田ヒカル"
        assertEquals(credit, albumArtistTag(credit, "Other"))
        assertEquals(credit, albumArtistTag(null, credit))
    }

    @Test fun trackNumbersKeepTheirOriginalNotation() {
        listOf("007", "3/12", "B2", "2.03", "Ⅳ").forEach {
            assertEquals(it, trackNumberTag(" $it \n"))
        }
    }

    @Test fun absentOrMalformedTrackNumbersStayUnknown() {
        listOf(null, "", " \n", 3, true, listOf("3"), mapOf("track" to 3), Any())
            .forEach { assertEquals("", trackNumberTag(it)) }
    }

    @Test fun olderCallersStillGetSafeDefaults() {
        val track = TauonTrack(1, "Song", "Artist", "Album", 1000, true, false)
        assertEquals("Artist", track.albumArtist)
        assertEquals("", track.trackNumber)
    }
}
