package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkSizeTest {
    @Test fun nothingIsDecodedUntilMeasured() {
        assertEquals(0, artworkSize(0))
    }

    /** A 52 dp list cover on a 2.625× screen is 137 px: decoded at 192, not at the server's 1000. */
    @Test fun aListCoverIsDecodedAtTheNextSizeUp() {
        assertEquals(192, artworkSize(137))
        assertEquals(128, artworkSize(126))
        assertEquals(128, artworkSize(128))
    }

    @Test fun thePlayersCoverUsesTheLargestSize() {
        assertEquals(1024, artworkSize(1050))
        assertEquals(1024, artworkSize(4000))
    }

    /** Tauon's medium picture is 1000 px; a 192 px row halves it twice and filters the rest. */
    @Test fun subsamplingStopsBeforeTheShorterSideFallsBelowTheTarget() {
        assertEquals(4, artworkSampleSize(1000, 1000, 192))
        assertEquals(2, artworkSampleSize(1000, 1000, 384))
        assertEquals(1, artworkSampleSize(1000, 1000, 1024))
        assertEquals(1, artworkSampleSize(75, 75, 192))
    }

    @Test fun theShorterSideDecides() {
        assertEquals(2, artworkSampleSize(2000, 500, 192))
    }

    /** A strip-shaped picture is subsampled further, so its long side stays bounded. */
    @Test fun aVeryLongPictureIsBounded() {
        assertEquals(8, artworkSampleSize(16000, 400, 192))
    }
}
