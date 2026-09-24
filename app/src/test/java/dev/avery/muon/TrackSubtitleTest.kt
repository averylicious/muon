package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSubtitleTest {
    @Test fun aFullyTaggedTrackReadsArtistThenAlbum() {
        assertEquals("Ahrix · Nova", trackSubtitle("Ahrix", "Nova", playable = true))
    }

    @Test fun aMissingTagTakesItsSeparatorWithIt() {
        assertEquals("Ahrix", trackSubtitle("Ahrix", "", playable = true))
        assertEquals("Nova", trackSubtitle("", "Nova", playable = true))
    }

    /** The case from #78: an untagged file showed a solitary dot. */
    @Test fun anUntaggedTrackHasNoSecondLineAtAll() {
        assertEquals("", trackSubtitle("", "", playable = true))
        assertEquals("", trackSubtitle("   ", "\n\t", playable = true))
    }

    /** Whitespace is not a tag; it is also not worth a separator. */
    @Test fun whitespaceCountsAsMissing() {
        assertEquals("Ahrix", trackSubtitle("  Ahrix  ", "   ", playable = true))
    }

    @Test fun anUnstreamableTrackSaysSoAndKeepsItsArtist() {
        assertEquals("Unavailable for direct streaming · Ahrix",
            trackSubtitle("Ahrix", "Nova", playable = false))
    }

    /** With no artist either, the reason stands alone rather than trailing a separator. */
    @Test fun anUnstreamableUntaggedTrackStillExplainsItself() {
        assertEquals("Unavailable for direct streaming", trackSubtitle("", "", playable = false))
        assertEquals("Unavailable for direct streaming", trackSubtitle(" ", "Nova", playable = false))
    }
}
