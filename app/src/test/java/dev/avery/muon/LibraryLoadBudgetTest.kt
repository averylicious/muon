package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test

class LibraryLoadBudgetTest {
    private val a = TauonPlaylist("1", "A", 1)
    private val b = TauonPlaylist("2", "B", 1)
    private fun song(title: String = "Song") = TauonTrack(42, title, "Artist", "Album", 180_000, true, false)
    private fun limits(entries: Int = 3, bytes: Long = 10_000) = LibraryLoadLimits(2, entries, bytes)
    private fun labelBytes(lists: List<TauonPlaylist>) = lists.sumOf {
        it.id.toByteArray(Charsets.UTF_8).size.toLong() + it.name.toByteArray(Charsets.UTF_8).size
    }

    @Test fun sharedIdsAndObjectsStillCountEveryPlaylistOccurrence() {
        val track = song()
        val budget = LibraryLoadBudget(listOf(a, b), limits())
        budget.add(listOf(track, track))
        budget.add(listOf(track)) // Exact count boundary is accepted.
        assertThrows(LibraryResourceLimit::class.java) { budget.add(listOf(track)) }
    }

    @Test fun unicodeLabelsAndNulSafeRecordsUseEncodedBytesWithoutTruncation() {
        val list = a.copy(name = "音楽 🎵")
        val track = song("Title\u0000Part 🎵").copy(artist = "Björk", albumArtist = "Various Artists", trackNumber = "3/12")
        val exact = labelBytes(listOf(list)) + encodeSong(track).size
        LibraryLoadBudget(listOf(list), limits(bytes = exact)).add(listOf(track))
        assertThrows(LibraryResourceLimit::class.java) {
            LibraryLoadBudget(listOf(list), limits(bytes = exact - 1)).add(listOf(track))
        }
        assertEquals(track, decodeSong(encodeSong(track)))
        assertEquals("Title\u0000Part 🎵", track.title)
    }

    @Test fun anOverBudgetBatchDoesNotConsumeTheNextRetryBudget() {
        val track = song()
        val budget = LibraryLoadBudget(listOf(a), limits(bytes = labelBytes(listOf(a)) + encodeSong(track).size))
        // The first item fits, the second fails. No counters are committed by the failed batch.
        assertThrows(LibraryResourceLimit::class.java) { budget.add(listOf(track, track)) }
        budget.add(listOf(track))
        assertThrows(LibraryResourceLimit::class.java) { budget.add(listOf(track)) }
    }

    @Test fun aFailedPlaylistFallbackIsIncludedBeforePublishingTheRefresh() {
        val retained = listOf(song("Old"), song("Old second"))
        val old = mapOf("2" to retained)
        val fresh = mapOf("1" to listOf(song("New"), song("New second")))
        assertThrows(LibraryResourceLimit::class.java) {
            combineBudgetedLoad(listOf(a, b), fresh, old, limits(entries = 3))
        }
        assertSame(retained, old["2"])
        assertEquals(2, fresh.getValue("1").size)
        val retry = combineBudgetedLoad(listOf(a, b), mapOf("1" to listOf(song("New"))), old, limits())
        assertEquals(listOf(a, b), retry.playlists)
        assertSame(retained, retry.tracks["2"])
        assertEquals(1, retry.failed)
    }

    @Test fun metadataOfRetainedFailedPlaylistsCannotBypassTheAggregateBudget() {
        val oldSong = song("Old")
        val newSong = song("New")
        val bytes = labelBytes(listOf(a, b)) + encodeSong(newSong).size
        assertThrows(LibraryResourceLimit::class.java) {
            combineBudgetedLoad(listOf(a, b), mapOf("1" to listOf(newSong)),
                mapOf("2" to listOf(oldSong)), limits(bytes = bytes))
        }
    }

    @Test fun partialSuccessPreservesTauonOrderAndCompleteMetadataWithinTheBudget() {
        val retained = listOf(song("Retained"))
        val fresh = listOf(song("Fresh"))
        val load = combineBudgetedLoad(listOf(b, a), mapOf("1" to fresh), mapOf("2" to retained), limits())
        assertEquals(listOf(b, a), load.playlists)
        assertEquals(listOf("2", "1"), load.tracks.keys.toList())
        assertSame(fresh, load.tracks["1"])
        assertSame(retained, load.tracks["2"])
        assertEquals(1, load.failed)
    }

    @Test fun playlistCountAndLabelsAreAlsoBounded() {
        assertThrows(LibraryResourceLimit::class.java) {
            LibraryLoadBudget(listOf(a, b, a), limits())
        }
        val labels = labelBytes(listOf(a, b))
        LibraryLoadBudget(listOf(a, b), limits(bytes = labels)).add(emptyList())
        assertThrows(LibraryResourceLimit::class.java) {
            LibraryLoadBudget(listOf(a, b), limits(bytes = labels - 1))
        }
    }
}
