package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncedLyricsTest {
    @Test fun parsesStampsInTimeOrder() {
        val lines = parseLrc("[00:12.50]Second\n[00:01.00]First\n[01:02.345]Third")!!
        assertEquals(listOf(LyricLine(1_000, "First"), LyricLine(12_500, "Second"), LyricLine(62_345, "Third")), lines)
    }

    @Test fun severalStampsRepeatALine() {
        val lines = parseLrc("[00:10.00][00:40.00]Chorus")!!
        assertEquals(listOf(LyricLine(10_000, "Chorus"), LyricLine(40_000, "Chorus")), lines)
    }

    @Test fun tenthsCentisecondsAndMillisecondsAllRead() {
        assertEquals(1_500L, parseLrc("[00:01.5]a")!!.single().atMs)
        assertEquals(1_050L, parseLrc("[00:01.05]a")!!.single().atMs)
        assertEquals(1_005L, parseLrc("[00:01.005]a")!!.single().atMs)
        assertEquals(1_000L, parseLrc("[00:01]a")!!.single().atMs)
    }

    /** LRC's offset is positive for earlier; no line moves before the song starts. */
    @Test fun offsetMovesEveryLineAndNeverBeforeZero() {
        val lines = parseLrc("[offset:+500]\n[00:02.00]a\n[00:00.20]b")!!
        assertEquals(listOf(LyricLine(0, "b"), LyricLine(1_500, "a")), lines)
    }

    @Test fun metadataIsIgnoredAndBlankLinesStayAsPauses() {
        val lines = parseLrc("[ar:Someone]\n[ti:Song]\n[00:01.00]Words\n[00:05.00]")!!
        assertEquals(listOf(LyricLine(1_000, "Words"), LyricLine(5_000, "")), lines)
    }

    @Test fun plainTextIsNotSynced() {
        assertNull(parseLrc("Just words\nno times"))
        assertNull(parseLrc(""))
    }

    @Test fun theSungLineIsTheLastThatHasStarted() {
        val lines = listOf(LyricLine(1_000, "a"), LyricLine(5_000, "b"), LyricLine(9_000, "c"))
        assertEquals(-1, currentLine(lines, 500))
        assertEquals(0, currentLine(lines, 1_000))
        assertEquals(1, currentLine(lines, 8_999))
        assertEquals(2, currentLine(lines, 60_000))
        assertEquals(-1, currentLine(emptyList(), 1_000))
    }

    @Test fun aSearchResultMustBeSyncedAndNearTheSongsLength() {
        val results = listOf(200.0 to null, 260.0 to "[00:01.00]far", 203.0 to "[00:01.00]near")
        assertEquals("[00:01.00]near", pickSearchResult(results, durationS = 200))
        assertEquals("[00:01.00]far", pickSearchResult(results, durationS = 0))
        assertNull(pickSearchResult(listOf(200.0 to ""), durationS = 200))
    }

    @Test fun theCacheKeyIgnoresCaseButNotLength() {
        assertEquals(syncedLyricsKey("Song", "Artist", 200), syncedLyricsKey("song", "ARTIST", 200))
        assertNotEquals(syncedLyricsKey("Song", "Artist", 200), syncedLyricsKey("Song", "Artist", 201))
    }
}
