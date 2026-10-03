package dev.avery.muon

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #179 S1 decisions over a shelf whose storage may be unavailable. Fakes stand in for shelves: these
 * check which shelf each decision picks, not what Media3 or a real mount does.
 */
class CardAvailabilityTest {
    @get:Rule val folders = TemporaryFolder()

    private class FakeShelf(val name: String, var present: Boolean = true,
        val finished: Set<String> = emptySet(), val queued: Set<String> = emptySet()) : ShelfState {
        override fun available() = present
        override fun completed(id: String) = id in finished
        override fun holds(id: String) = id in finished || id in queued
        override fun toString() = name
    }

    private val phone = FakeShelf("phone", finished = setOf("p"))
    private val card = FakeShelf("card", finished = setOf("c", "both"), queued = setOf("q"))

    @Test fun anAvailableCardStillServesItsSongs() {
        assertSame(card, servingShelf(listOf(phone, card), "c"))
        assertSame(phone, servingShelf(listOf(phone, card), "p"))
    }

    @Test fun anUnavailableCardServesNothingAndThePhoneStillDoes() {
        card.present = false
        assertNull(servingShelf(listOf(phone, card), "c"))
        assertSame(phone, servingShelf(listOf(phone, card), "p"))
        assertEquals(listOf(phone), availableShelves(listOf(phone, card)))
    }

    @Test fun choosingAnUnavailableCardIsRefusedNotSentToThePhone() {
        assertEquals(DownloadTarget.Phone, downloadTarget(card, onCard = false))
        assertEquals(DownloadTarget.Card, downloadTarget(card, onCard = true))
        card.present = false
        assertEquals(DownloadTarget.CardUnavailable, downloadTarget(card, onCard = true))
        // The card chosen, but none found when Muon opened: still not the phone.
        assertEquals(DownloadTarget.CardUnavailable, downloadTarget(null, onCard = true))
        assertEquals(DownloadTarget.Phone, downloadTarget(null, onCard = false))
    }

    @Test fun removingWithTheCardAwaySendsNothingToItAndWithholdsItsSongs() {
        card.present = false
        val plan = removalPlan(listOf(phone, card), listOf("p", "c", "q", "elsewhere"))
        assertEquals(listOf(phone to listOf("p", "c", "q", "elsewhere")), plan.commands)
        // Withheld, not removed and not reported as gone: finished and still-queued alike.
        assertEquals(listOf("c", "q"), plan.withheld)
    }

    @Test fun removingWithTheCardInSendsToBothAndWithholdsNothing() {
        val plan = removalPlan(listOf(phone, card), listOf("c"))
        assertEquals(listOf(phone, card), plan.commands.map { it.first })
        assertTrue(plan.withheld.isEmpty())
    }

    @Test fun aMoveLeftoverOnAnUnavailableCardIsKept() {
        // "both" finished on the phone; the card's copy is a leftover to remove only while it is there.
        val phoneDone = FakeShelf("phone", finished = setOf("both"))
        assertEquals(listOf(card), leftoverCopies(phoneDone, listOf(phoneDone, card), "both"))
        card.present = false
        assertTrue(leftoverCopies(phoneDone, listOf(phoneDone, card), "both").isEmpty())
    }

    @Test fun aCompletionReportedForAnAbsentCardRemovesNothing() {
        // The card's manager reports "both" finished after the card has gone: the phone's copy, perhaps
        // the only real one, must not be removed on the strength of it.
        val phoneHas = FakeShelf("phone", finished = setOf("both"))
        card.present = false
        assertTrue(leftoverCopies(card, listOf(phoneHas, card), "both").isEmpty())
    }

    @Test fun aMoveNeedsBothShelvesAndStopsWhenOneGoes() {
        assertTrue(canMove(phone, card))
        assertFalse(canMove(phone, null))
        card.present = false
        assertFalse(canMove(phone, card))
        assertFalse(canMove(card, phone))
    }

    @Test fun aCopyFinishedJustBeforeTheDestinationWentIsNotHandedOver() {
        // The stale callback: phone to card copied, then the card went before the main thread ran.
        var sent = 0
        assertTrue(deliverMovedCopy(phone, card) { sent++ })
        card.present = false
        assertFalse(deliverMovedCopy(phone, card) { sent++ })
        assertEquals(1, sent)
    }

    @Test fun aCopyFinishedJustBeforeTheSourceWentIsNotHandedOver() {
        // Card to phone copied, then the card went: handing it over would make the phone's completion
        // remove the source copy on a card that isn't there.
        var sent = 0
        card.present = false
        assertFalse(deliverMovedCopy(card, phone) { sent++ })
        assertEquals(0, sent)
    }

    @Test fun anAbsentFolderIsNotCreatedToFindOut() {
        // Plain JVM: android.os.Environment is a stub that throws, so this only shows the check fails
        // closed and creates nothing. Mount states are checked in CardAvailabilityRouteTest.
        val missing = File(folders.root, "card/Android/data/dev.avery.muon/files")
        assertFalse(cardPresent(missing))
        assertFalse(missing.exists())
        assertFalse(missing.parentFile!!.exists())
    }
}
