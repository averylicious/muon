package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

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
    @Test fun admittedFreshRecordsAreNotWalkedAgainDuringFinalAssembly() {
        val track=song("Fresh");var visits=0
        val fresh=object:AbstractList<TauonTrack>() {
            override val size=2
            override fun get(index:Int):TauonTrack {check(index in 0..1);visits++;return track}
        }
        val load=BudgetedLibraryLoad(listOf(a),limits())
        load.add(a.id,fresh);val admittedVisits=visits;assertTrue(admittedVisits>0)
        val previous=mapOf(a.id to listOf(song("Old")))
        val result=load.finish(previous)
        assertEquals(admittedVisits,visits);assertSame(fresh,result.tracks[a.id])
        assertEquals(0,result.failed);assertEquals("Old",previous.getValue(a.id).single().title)
        assertThrows(IllegalStateException::class.java) {load.finish(previous)}
        assertThrows(IllegalStateException::class.java) {load.add(a.id,emptyList())}
    }
    @Test fun preAdmittedFreshLoadStillRefusesOverBudgetFallbackWithoutChangingOldLibrary() {
        val fresh=listOf(song("Fresh"));val retained=listOf(song("Old"),song("Old second"))
        val previous=mapOf(b.id to retained)
        val exact=labelBytes(listOf(a,b))+encodeSong(fresh.single()).size+encodeSong(retained.first()).size
        val load=BudgetedLibraryLoad(listOf(a,b),limits(entries=3,bytes=exact))
        load.add(a.id,fresh)
        assertThrows(LibraryResourceLimit::class.java) {load.finish(previous)}
        assertSame(retained,previous[b.id]);assertEquals(listOf("Old","Old second"),retained.map {it.title})
        assertThrows(IllegalStateException::class.java) {load.add(b.id,emptyList())}
    }
    @Test fun refusedBatchesAndInvalidPlaylistIdsCannotSpendOrDuplicateAdmission() {
        val load=BudgetedLibraryLoad(listOf(a,b),limits(entries=2))
        assertThrows(LibraryResourceLimit::class.java) {load.add(a.id,List(3) {song()})}
        load.add(a.id,listOf(song("A")))
        assertThrows(IllegalArgumentException::class.java) {load.add(a.id,listOf(song("Duplicate")))}
        assertThrows(IllegalArgumentException::class.java) {load.add("99",listOf(song("Unknown")))}
        load.add(b.id,listOf(song("B")))
        val result=load.finish(null)
        assertEquals(listOf(a,b),result.playlists);assertEquals(0,result.failed)
        assertEquals(listOf("A","B"),result.tracks.values.map {it.single().title})
        // Convenience callers preserve combineLoad's policy of ignoring unlisted map entries.
        val ignored=combineBudgetedLoad(listOf(a),mapOf(a.id to listOf(song()),"99" to List(3) {song()}),null,limits(entries=1))
        assertEquals(setOf(a.id),ignored.tracks.keys)
    }

    @Test fun actualJobCancellationStopsFreshAndFallbackAdmissionWithoutPublishingOrSpendingBudget() {
        var reads = 0
        fun cancellingRows(job: Job) = object:AbstractList<TauonTrack>() {
            override val size = 2
            override fun get(index: Int): TauonTrack {
                check(index in 0..1); reads++; job.cancel(); return song()
            }
        }
        val job = Job()
        val load = BudgetedLibraryLoad(listOf(a),limits(entries=2))
        assertThrows(CancellationException::class.java) {
            load.add(a.id,cancellingRows(job)) { job.ensureActive() }
        }
        assertEquals(1,reads) // Remaining record was never read/encoded or entered in the fresh map.
        val fresh = listOf(song("Fresh"),song("Second"))
        load.add(a.id,fresh)
        assertSame(fresh,load.finish(null).tracks[a.id]) // Cancelled batch spent no admission budget.
        assertThrows(CancellationException::class.java) {
            BudgetedLibraryLoad(listOf(a),limits()) { job.ensureActive() }
        }
        val fallbackJob = Job();val retained = cancellingRows(fallbackJob)
        val previous = mapOf(b.id to retained);reads=0
        val partial = BudgetedLibraryLoad(listOf(a,b),limits())
        partial.add(a.id,listOf(song("New")))
        assertThrows(CancellationException::class.java) {
            partial.finish(previous) { fallbackJob.ensureActive() }
        }
        assertEquals(1,reads);assertSame(retained,previous[b.id])
        assertThrows(IllegalStateException::class.java) { partial.finish(previous) }
    }

    @Test fun expandedCreditsAreChargedExactlyAndRefusedBatchesSpendNoBudget() {
        val named = song().copy(artist = " A ; a ; ; B/C & D, E ")
        val unknown = song("Unknown").copy(artist = " ; ; ")
        val limit = limits().copy(artistMemberships = 3)
        val budget = LibraryLoadBudget(listOf(a), limit)
        assertThrows(LibraryResourceLimit::class.java) { budget.add(listOf(named, named)) }
        budget.add(listOf(named, unknown)) // Two distinct credits and one unknown: exact boundary.
        assertEquals(3, groupArtists(listOf(named.copy(id = 1), unknown.copy(id = 2))).sumOf { it.tracks.size })
        assertThrows(LibraryResourceLimit::class.java) { budget.add(listOf(unknown)) }
        LibraryLoadBudget(listOf(a), limit.copy(artistMemberships = 0)).add(emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            LibraryLoadBudget(listOf(a), limit.copy(artistMemberships = -1))
        }
    }

    @Test fun fallbackAndRepeatedOccurrencesCannotBypassArtistMembershipAdmission() {
        val credits = song().copy(artist = "One; Two")
        val retained = listOf(credits)
        val previous = mapOf(b.id to retained)
        val limit = limits().copy(artistMemberships = 3)
        val load = BudgetedLibraryLoad(listOf(a, b), limit)
        load.add(a.id, listOf(credits))
        assertThrows(LibraryResourceLimit::class.java) { load.finish(previous) }
        assertSame(retained, previous[b.id]); assertEquals("One; Two", retained.single().artist)
        val retry = combineBudgetedLoad(listOf(a, b), mapOf(a.id to listOf(credits)), previous,
            limit.copy(artistMemberships = 4))
        assertEquals(listOf(a, b), retry.playlists); assertSame(retained, retry.tracks[b.id])
        // Subset maps ignored by combineLoad are not charged; record text/entry budgets still apply.
        val ignored = combineBudgetedLoad(listOf(a), mapOf(a.id to listOf(song()), b.id to List(3) { credits }),
            null, limit.copy(artistMemberships = 1))
        assertEquals(setOf(a.id), ignored.tracks.keys)
    }

}
