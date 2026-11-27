package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverPlaceholderTest {
    /** Icon-sized notes (rows, the mini player, grid tiles) keep the icon set's stroke. */
    @Test fun aSmallNoteKeepsTheIconStroke() {
        assertEquals(2f, placeholderNoteStroke(18f), 0f)
        assertEquals(2f, placeholderNoteStroke(40f), 0f)
    }

    /** Now Playing's large cover thins the stroke so it isn't a blown-up icon. */
    @Test fun aLargeNoteThinsItsStroke() {
        assertEquals(1.5f, placeholderNoteStroke(120f), 0f)
    }
}
