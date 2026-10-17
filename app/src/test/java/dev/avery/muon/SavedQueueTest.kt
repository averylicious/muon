package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedQueueTest {
    @Test fun fullCompleteOrderAndSelectedIndexKeepDuplicateSongsAndAllDisplayFields() {
        val first = entry("saved/a")
        val second = entry("saved/b", shelf = SavedShelf.Card, cover = true)
        val incomplete = entry("saved/partial").copy(coverage = SavedCoverage.Partial)
        val queue = requireNotNull(prepareSavedQueue(listOf(incomplete, first, second), second.ref))
        assertEquals(1, queue.startIndex)
        assertEquals(listOf(first.ref.handle, second.ref.handle), queue.items.map { it.mediaId })
        for ((copy, item) in listOf(first, second).zip(queue.items)) {
            assertEquals(copy.ref.handle, item.localConfiguration?.uri.toString())
            assertEquals(copy.title(), item.mediaMetadata.title.toString())
            assertEquals(copy.subtitle(), item.mediaMetadata.artist.toString())
            assertEquals(copy.displaySong?.album, item.mediaMetadata.albumTitle.toString())
        }
        assertEquals(savedArtUrl(second.ref.requestId), queue.items[1].mediaMetadata.artworkUri.toString())
        assertNotEquals(queueOccurrenceKey(queue.items[0]), queueOccurrenceKey(queue.items[1]))
    }

    @Test fun aQueueBeyondTheCountLimitIsRefusedWholeAndAChosenSingleCopyStillPlays() {
        val entries = listOf(entry("saved/a"), entry("saved/b"), entry("saved/c"))
        assertThrows(SavedQueueLimit::class.java) { prepareSavedQueue(entries, entries[2].ref, SavedQueueLimits(items = 2)) }
        assertEquals(3, entries.size) // No input truncated, removed or rewritten.
        val smaller = requireNotNull(prepareSavedQueue(listOf(entries[2]), entries[2].ref, SavedQueueLimits(items = 2)))
        assertEquals(listOf(entries[2].ref.handle), smaller.items.map { it.mediaId })
        assertEquals(0, smaller.startIndex)
        assertEquals(2, requireNotNull(prepareSavedQueue(entries.take(2), entries[1].ref, SavedQueueLimits(items = 2))).items.size)
    }

    @Test fun utf8MetadataBudgetIncludesLocatorsArtAndOccurrenceAndAcceptsItsExactBoundary() {
        val selected = entry("saved/utf8", title = "宇多田🎵", cover = true)
        val actual = requireNotNull(prepareSavedQueue(listOf(selected), selected.ref)).items.single()
        val fields = listOf(actual.mediaId, actual.localConfiguration?.uri.toString(), actual.mediaMetadata.title.toString(),
            actual.mediaMetadata.artist.toString(), actual.mediaMetadata.albumTitle.toString(), actual.mediaMetadata.artworkUri.toString(),
            requireNotNull(queueOccurrenceKey(actual)))
        val exact = fields.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }
        assertNotNull(prepareSavedQueue(listOf(selected), selected.ref, SavedQueueLimits(textBytes = exact)))
        assertThrows(SavedQueueLimit::class.java) { prepareSavedQueue(listOf(selected), selected.ref, SavedQueueLimits(textBytes = exact - 1)) }
        val charCount = fields.sumOf { it.length.toLong() }
        assertTrue(exact > charCount)
        assertThrows(SavedQueueLimit::class.java) { prepareSavedQueue(listOf(selected), selected.ref, SavedQueueLimits(textBytes = charCount)) }
    }

    @Test fun absentOrIncompleteSelectionNeverFallsBackToAnotherCopy() {
        val good = entry("saved/a")
        val other = entry("saved/b").copy(coverage = SavedCoverage.Partial)
        assertNull(prepareSavedQueue(listOf(good, other), other.ref))
        assertNull(prepareSavedQueue(emptyList(), good.ref))
    }

    @Test fun repeatedLocatorsRemainSeparateOccurrencesAndFirstSelectionMatchesExistingBehavior() {
        val copy = entry("saved/a")
        val queue = requireNotNull(prepareSavedQueue(listOf(copy, copy), copy.ref))
        assertEquals(0, queue.startIndex)
        assertEquals(listOf(copy.ref.handle, copy.ref.handle), queue.items.map { it.mediaId })
        assertNotEquals(queueOccurrenceKey(queue.items[0]), queueOccurrenceKey(queue.items[1]))
    }

    @Test fun theProductionCountBoundaryKeepsEveryItemAndRejectsTheNextWithoutTruncating() {
        val entries = List(2_049) { entry("saved/$it") }
        val allowed = requireNotNull(prepareSavedQueue(entries.take(2_048), entries[2_047].ref))
        assertEquals(2_048, allowed.items.size)
        assertEquals(2_047, allowed.startIndex)
        assertThrows(SavedQueueLimit::class.java) { prepareSavedQueue(entries, entries.last().ref) }
        assertEquals(2_049, entries.size)
    }

    private fun entry(id: String, title: String = "Same song", shelf: SavedShelf = SavedShelf.Phone, cover: Boolean = false): SavedEntry =
        SavedEntry(requireNotNull(SavedRef.download(shelf, id, id)), TauonTrack(42, title, "Artist", "Album", 1000, true, false),
            "http://127.0.0.1:7814", androidx.media3.exoplayer.offline.Download.STATE_COMPLETED, SavedCoverage.Full, 4, cover, true)
}
