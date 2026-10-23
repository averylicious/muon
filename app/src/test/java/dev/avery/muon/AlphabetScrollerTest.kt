package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetScrollerTest {
    @Test fun aTitleFilesUnderItsFirstLetterUppercasedWithoutAccents() {
        assertEquals("A", sectionLetter("angels"))
        assertEquals("B", sectionLetter("Bright Side"))
        assertEquals("E", sectionLetter("élan"))
        assertEquals("O", sectionLetter("Ökologie"))
        assertEquals("A", sectionLetter("  astronomia"))
    }

    @Test fun digitsSymbolsAndBlanksFileUnderHash() {
        assertEquals("#", sectionLetter("12 Feet Deep"))
        assertEquals("#", sectionLetter("(hed) Chaos"))
        assertEquals("#", sectionLetter(""))
        assertEquals("#", sectionLetter("   "))
    }

    @Test fun scriptsWithoutCaseGiveTheirFirstCharacter() {
        assertEquals("坂", sectionLetter("坂本龍一"))
        assertEquals("Ж", sectionLetter("жизнь"))
    }

    @Test fun uppercasingDoesNotDependOnTheDeviceLocale() {
        // In a Turkish locale "i" would become a dotted capital İ.
        assertEquals("I", sectionLetter("istanbul"))
    }

    @Test fun theThumbPositionPointsAtARowInTheList() {
        assertEquals(0, scrollerIndex(0f, 962))
        assertEquals(961, scrollerIndex(1f, 962))
        assertEquals(481, scrollerIndex(0.5f, 963))
        // Out-of-range fractions stay on the list, and an empty list has only row 0.
        assertEquals(0, scrollerIndex(-0.2f, 10))
        assertEquals(9, scrollerIndex(1.7f, 10))
        assertEquals(0, scrollerIndex(0.5f, 0))
    }
    @Test fun aViewportShorterThanTheHandleOrBubbleHasNoNegativeClampRange() {
        for (element in listOf(48f, 56f)) {
            assertEquals(0f, scrollerOffset(24f, element, 0f), 0f)
            assertEquals(0f, scrollerOffset(24f, element, 20f), 0f)
            assertEquals(0f, scrollerOffset(100f, element, element), 0f)
        }
    }

    @Test fun normalViewportOffsetsFollowTheCenterAndStayInsideBothEdges() {
        assertEquals(76f, scrollerOffset(100f, 48f, 400f), 0f)
        assertEquals(0f, scrollerOffset(-10f, 48f, 400f), 0f)
        assertEquals(352f, scrollerOffset(450f, 48f, 400f), 0f)
        assertEquals(344f, scrollerOffset(450f, 56f, 400f), 0f)
    }
}
