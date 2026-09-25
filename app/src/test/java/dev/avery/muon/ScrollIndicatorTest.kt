package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class ScrollIndicatorTest {
    // A 1000 px list of 100 px rows: ten rows fit.
    private fun thumb(first: Int, offset: Int = 0, total: Int = 100, atStart: Boolean = first == 0 && offset == 0,
        atEnd: Boolean = false) =
        scrollThumb(first, offset, averageItem = 100f, total = total, viewport = 1000f, minLength = 32f,
            atStart = atStart, atEnd = atEnd)

    @Test fun aListThatFitsOrHasNothingMeasuredShowsNothing() {
        assertNull(thumb(0, total = 10))
        assertNull(thumb(0, total = 5))
        assertNull(thumb(0, total = 0))
        assertNull(scrollThumb(0, 0, 0f, 100, 1000f, 32f, atStart = true, atEnd = false))
        // Can scroll neither way: nothing to indicate, whatever the estimate says.
        assertNull(thumb(0, total = 100, atStart = true, atEnd = true))
    }

    @Test fun theThumbIsTheVisibleShareOfTheList() {
        // A tenth of the list is on screen, so the thumb is a tenth of the track.
        assertEquals(100f, thumb(0)!!.length, 0.01f)
        // A fifth on screen, a fifth of the track.
        assertEquals(200f, thumb(0, total = 50)!!.length, 0.01f)
    }

    @Test fun aVeryLongListStillHasAThumbYouCanSee() {
        // 962 songs: the true share would be about 10 px; it never goes below the minimum.
        assertEquals(32f, thumb(0, total = 962)!!.length, 0.01f)
    }

    @Test fun theEndsAreExactAndTheMiddleIsProportional() {
        assertEquals(0f, thumb(0)!!.start, 0.01f)
        val end = thumb(90, atEnd = true)!!
        assertEquals(1000f, end.start + end.length, 0.01f)
        // Half way through the scrollable distance (4500 of 9000 px) puts the thumb half way down.
        val middle = thumb(45)!!
        assertEquals((1000f - middle.length) / 2, middle.start, 0.01f)
        // The offset into the first row counts.
        assertTrue(thumb(45, offset = 50)!!.start > middle.start)
    }

    @Test fun anEstimateThatOvershootsIsKeptOnTheTrack() {
        // Rows taller than the average of those on screen can put the estimate past the end.
        val over = thumb(99, offset = 500)!!
        assertTrue(over.start + over.length <= 1000f + 0.01f)
        assertTrue(over.start >= 0f)
    }

    @Test fun theThumbNeverOutgrowsTheTrack() {
        val tiny = scrollThumb(0, 0, averageItem = 100f, total = 20, viewport = 20f, minLength = 32f,
            atStart = true, atEnd = false)!!
        assertTrue(tiny.length <= 20f)
    }
}
