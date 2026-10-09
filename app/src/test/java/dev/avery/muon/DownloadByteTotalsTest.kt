package dev.avery.muon

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * DownloadByteTotals against the behaviour it replaces: a map of sizes summed in full after every change
 * (the old `sizes.values.sum()`), including its wrapping on overflow.
 */
class DownloadByteTotalsTest {
    /** The old accounting, kept here only as the reference. */
    private class Reference {
        val sizes = HashMap<String, Long>()
        fun put(id: String, bytes: Long) { sizes[id] = bytes }
        fun remove(id: String) { sizes.remove(id) }
        val total get() = sizes.values.sum()
    }

    @Test fun replacementsRemovalsUnknownIdsAndZeroSizesMatchTheFullSum() {
        val totals = DownloadByteTotals()
        val reference = Reference()
        fun put(id: String, bytes: Long) { totals.put(id, bytes); reference.put(id, bytes); assertEquals(reference.total, totals.total) }
        fun remove(id: String) { totals.remove(id); reference.remove(id); assertEquals(reference.total, totals.total) }

        put("a", 100)
        put("b", 250)
        put("a", 40) // A later completion replaces the earlier size; one entry per id.
        assertEquals(290L, totals.total)
        remove("never-recorded") // Unknown: no change.
        put("zero", 0)
        remove("zero")
        // A song finished, then queued again elsewhere (removed), then finished again.
        remove("b")
        put("b", 300)
        remove("a")
        remove("a") // Already gone.
        assertEquals(300L, totals.total)
    }

    @Test fun overflowWrapsExactlyAsTheOldSumDid() {
        val totals = DownloadByteTotals()
        totals.put("big", Long.MAX_VALUE)
        totals.put("more", 2)
        assertEquals(listOf(Long.MAX_VALUE, 2L).sum(), totals.total)
        assertEquals(Long.MIN_VALUE + 1, totals.total)
        totals.remove("big")
        assertEquals(2L, totals.total)
        totals.put("more", Long.MAX_VALUE)
        totals.put("other", Long.MAX_VALUE)
        assertEquals(listOf(Long.MAX_VALUE, Long.MAX_VALUE).sum(), totals.total)
    }

    @Test fun aLongMixedSequenceAlwaysMatchesTheFullSum() {
        val random = Random(253)
        val sizes = longArrayOf(0, 1, 4_096, 7_340_032, 1L shl 40, Long.MAX_VALUE, Long.MAX_VALUE - 1)
        val totals = DownloadByteTotals()
        val reference = Reference()
        repeat(5_000) { step ->
            val id = "http://192.168.1.20:7814/${random.nextInt(40)}"
            if (random.nextInt(3) == 0) {
                totals.remove(id); reference.remove(id)
            } else {
                val bytes = sizes[random.nextInt(sizes.size)]
                totals.put(id, bytes); reference.put(id, bytes)
            }
            assertEquals("step $step", reference.total, totals.total)
        }
    }
}
