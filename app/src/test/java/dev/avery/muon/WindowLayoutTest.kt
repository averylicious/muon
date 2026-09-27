package dev.avery.muon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    /** A typical phone on its side: about 800 × 380 dp once the status bar is taken off. */
    @Test fun aPhoneInLandscapeIsSideways() {
        assertTrue(sidewaysLayout(widthDp = 800f, heightDp = 380f))
    }

    @Test fun aPhoneInPortraitIsNot() {
        assertFalse(sidewaysLayout(widthDp = 411f, heightDp = 860f))
    }

    /** A tablet in landscape has the height to stack, so it keeps the bottom bar. */
    @Test fun aTallLandscapeWindowIsNot() {
        assertFalse(sidewaysLayout(widthDp = 1280f, heightDp = 800f))
    }

    /** A short split-screen pane has no room beside anything either; it stacks and scrolls. */
    @Test fun aShortNarrowWindowIsNot() {
        assertFalse(sidewaysLayout(widthDp = 320f, heightDp = 400f))
    }

    @Test fun theDecisionTurnsOnMaterialsCompactHeight() {
        assertTrue(sidewaysLayout(widthDp = 900f, heightDp = 479.9f))
        assertFalse(sidewaysLayout(widthDp = 900f, heightDp = 480f))
    }
}
