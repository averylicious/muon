package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class CarouselLabelTest {
    @Test fun labelShowsOnlyOnLargeCovers() {
        assertEquals(0f, carouselLabelAlpha(40f, 40f, 200f), 0f)
        assertEquals(0f, carouselLabelAlpha(120f, 40f, 200f), 0f)
        assertEquals(0.5f, carouselLabelAlpha(160f, 40f, 200f), 0.001f)
        assertEquals(1f, carouselLabelAlpha(200f, 40f, 200f), 0f)
    }

    @Test fun equalSizesShowTheLabel() {
        assertEquals(1f, carouselLabelAlpha(100f, 100f, 100f), 0f)
    }
}
