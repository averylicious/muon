package dev.avery.muon

import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** On this device: per-page sections, the listening collapse and the storage-update offer. */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SavedScreenPresentationTest {
    private fun download(id: String, complete: Boolean = true, shelf: SavedShelf = SavedShelf.Phone) =
        SavedEntry(requireNotNull(SavedRef.download(shelf, id, id)), null, null,
            if (complete) Download.STATE_COMPLETED else Download.STATE_DOWNLOADING,
            if (complete) SavedCoverage.Full else SavedCoverage.Partial, 1000L, ownCover = true, removable = false)

    private fun played(id: String) =
        SavedEntry(requireNotNull(SavedRef.played("${PLAYED_PREFIX}$id")), null, null, null,
            SavedCoverage.Full, 1000L, ownCover = false, removable = true)

    @Test fun aPageSplitsBySourceKeepingCatalogOrderAndEveryCopy() {
        // Catalog order mixes sources and repeats a song as two different copies; none may merge or drop.
        val page = listOf(download("b"), played("a"), download("c"), played("b"), download("a"))
        val sections = savedPageSections(page)
        assertEquals(listOf("b", "c", "a"), sections.downloads.map { it.ref.key })
        assertEquals(listOf("${PLAYED_PREFIX}a", "${PLAYED_PREFIX}b"), sections.listening.map { it.ref.key })
        assertEquals(page.map { it.ref.handle }.toSet(), (sections.downloads + sections.listening).map { it.ref.handle }.toSet())
        assertEquals(page.size, sections.downloads.size + sections.listening.size)
    }

    @Test fun sectionsAreScopedToThePageWhenMoreThanOnePageExists() {
        assertFalse(savedPaged(SAVED_PAGE_SIZE.toLong(), 0))
        assertTrue(savedPaged(SAVED_PAGE_SIZE + 1L, 0))
        assertTrue(savedPaged(SAVED_PAGE_SIZE + 1L, SAVED_PAGE_SIZE.toLong()))
    }

    @Test fun listeningStartsCollapsedButNeverHidesAPagesOnlyRowsOrTheCurrentCopy() {
        val mixed = savedPageSections(listOf(download("a"), played("b")))
        assertFalse(savedListeningShown(false, mixed, current = null))
        assertTrue(savedListeningShown(true, mixed, current = null))
        // The copy that is playing stays visible even while the section is collapsed.
        assertTrue(savedListeningShown(false, mixed, current = played("b").ref.handle))
        // A later page holding only listening copies is never shown as empty.
        assertTrue(savedListeningShown(false, savedPageSections(listOf(played("c"))), current = null))
    }

    @Test fun storageUpdateIsOfferedOnlyOnAConfirmedCheckForThatCopy() = runTest {
        val checked = mutableListOf<String>()
        val yes = { key: String -> checked.add(key); true }
        val io = StandardTestDispatcher(testScheduler)
        // No check at all for copies that can't be updated: listening, incomplete, missing card, no storage.
        assertFalse(storageUpdateConfirmed(played("x"), cardMissing = false, check = yes, io = io))
        assertFalse(storageUpdateConfirmed(download("x", complete = false), cardMissing = false, check = yes, io = io))
        assertFalse(storageUpdateConfirmed(download("x"), cardMissing = true, check = yes, io = io))
        assertFalse(storageUpdateConfirmed(download("x"), cardMissing = false, check = null, io = io))
        assertTrue(checked.isEmpty())
        // Only a true answer for exactly this copy offers it; a failed check is a no, not a guess.
        assertTrue(storageUpdateConfirmed(download("x"), cardMissing = false, check = yes, io = io))
        assertEquals(listOf("x"), checked)
        assertFalse(storageUpdateConfirmed(download("y"), cardMissing = false, check = { false }, io = io))
        assertFalse(storageUpdateConfirmed(download("y"), cardMissing = false,
            check = { throw java.io.IOException("storage unavailable") }, io = io))
    }

    @Test fun closingTheMenuCancelsTheCheckBeforeItAnswers() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        var ran = false
        val pending = async { storageUpdateConfirmed(download("x"), cardMissing = false, check = { ran = true; true }, io = io) }
        pending.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(pending.isCancelled)
        assertFalse(ran)
        // A cancellation raised inside the check is not turned into a "no".
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                storageUpdateConfirmed(download("x"), cardMissing = false, check = { throw CancellationException("closed") })
            }
        }
    }
}
