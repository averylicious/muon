package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackCreditsTest {
    @Test fun semicolonCreditsReadAsAList() {
        assertEquals("A Flow Mobz, Luna Blake", displayCredits("A Flow Mobz; Luna Blake"))
        assertEquals("Syn Cole, Lucas Silow, Miss Buttons", displayCredits("Syn Cole;Lucas Silow ;  Miss Buttons"))
    }

    @Test fun aSingleArtistIsUnchangedAndOtherSeparatorsStayNames() {
        assertEquals("Avicii", displayCredits("Avicii"))
        // Only ';' separates credits, as in the artist grouping: '&' and '/' belong to names.
        assertEquals("Lucas & Steve", displayCredits("Lucas & Steve"))
        assertEquals("AC/DC", displayCredits("AC/DC"))
    }

    @Test fun blanksAndRepeatsAreDropped() {
        assertEquals("", displayCredits(""))
        assertEquals("", displayCredits(" ; ;"))
        assertEquals("Vicetone, Bonnie McKee", displayCredits("Vicetone; ; vicetone; Bonnie McKee"))
    }

    @Test fun theListSubtitleUsesTheReadableCredits() {
        assertEquals("A Flow Mobz, Luna Blake · Thrill Over Fear",
            trackSubtitle("A Flow Mobz; Luna Blake", "Thrill Over Fear", playable = true))
    }

    @Test fun theAlbumLineIsLeftOutWhenItRepeatsTheTitle() {
        assertNull(albumLine("Thrill Over Fear", "Thrill Over Fear"))
        assertNull(albumLine("Thrill Over Fear", "  thrill over fear "))
        assertNull(albumLine("Alone", ""))
        assertNull(albumLine("Alone", null))
        assertEquals("Stories", albumLine("Waiting For Love", "Stories"))
        assertEquals("Stories", albumLine(null, " Stories "))
    }
}
