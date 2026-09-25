package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackTitleTest {
    @Test fun nonblankTagsWinAndArePreserved() {
        assertEquals("  A / B  ", trackDisplayTitle("  A / B  ", "/music/file.flac"))
        assertEquals("Untitled", trackDisplayTitle("Untitled", "/music/file.flac"))
    }

    @Test fun blankOrMissingTagsUseOnlyFilename() {
        assertEquals("Robert Falcon - Heart Of Gold.flac",
            trackDisplayTitle("", "/private/music/Robert Falcon - Heart Of Gold.flac"))
        assertEquals("song.FLAC", trackDisplayTitle(null, "C:\\Users\\person\\Music\\song.FLAC"))
        assertEquals("song.flac", trackDisplayTitle(" \n", "C:\\Music/album/song.flac"))
        assertEquals("曲.flac", trackDisplayTitle("", "曲.flac"))
        assertEquals("01. Song", trackDisplayTitle(null, "/music/01. Song"))
    }

    @Test fun unusableFilenamesHaveDeterministicFallback() {
        listOf(null, "", " ", "/", "/music/", "C:\\Music\\", ".", "..", "/music/..").forEach {
            assertEquals("Untitled", trackDisplayTitle(null, it))
        }
    }

    @Test fun resolvedTitleIsSearchableWithoutLeakingDirectory() {
        val title = trackDisplayTitle("", "/private/collection/Heart Of Gold.flac")
        val track = TauonTrack(1043, title, "", "", 173165, true, false)
        assertEquals(listOf(track), searchTracks(listOf(track), "heart of gold"))
        assertEquals(emptyList<TauonTrack>(), searchTracks(listOf(track), "private"))
        assertFalse(title.contains('/'))
        assertFalse(title.contains('\\'))
        assertEquals(1043L, track.id)
    }
}
