package dev.avery.muon

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class QueueShuffleOrderTest {
    private fun order(shuffle: androidx.media3.exoplayer.source.ShuffleOrder): List<Int> {
        val out = mutableListOf<Int>()
        var i = shuffle.getFirstIndex()
        while (i != C.INDEX_UNSET) { out += i; i = shuffle.getNextIndex(i) }
        return out
    }

    @Test fun aNewQueueStartsWithTheChosenSongAndHoldsEveryOther() {
        repeat(50) { seed ->
            val shuffle = QueueShuffleOrder(Random(seed.toLong())).cloneAndSet(20, 13)
            val played = order(shuffle)
            assertEquals(13, played.first())
            assertEquals((0 until 20).toSet(), played.toSet())
            assertEquals(20, played.size)
        }
    }

    @Test fun withoutAChosenSongItIsStillAFullShuffle() {
        val played = order(QueueShuffleOrder(Random(1)).cloneAndSet(10, C.INDEX_UNSET))
        assertEquals((0 until 10).toSet(), played.toSet())
    }

    @Test fun aSongInsertedAfterThePlayingOneIsPlayedNext() {
        val start = QueueShuffleOrder.startingWith(10, 4, Random(7))
        // Play next: inserted into the queue straight after the playing song, index 4.
        val shuffle = start.cloneAndInsert(5, 1)
        val played = order(shuffle)
        assertEquals(listOf(4, 5), played.take(2))
        assertEquals(11, played.size)
        // Everything else keeps its order, renumbered past the insertion.
        assertEquals(start.order().drop(1).map { if (it >= 5) it + 1 else it }, played.drop(2))
    }

    @Test fun anAppendedSongGoesToTheEndOfTheShuffle() {
        val start = QueueShuffleOrder.startingWith(8, 2, Random(3))
        val played = order(start.cloneAndInsert(8, 2))
        assertEquals(listOf(8, 9), played.takeLast(2))
        assertEquals(start.order(), played.dropLast(2))
    }

    @Test fun insertingAtTheFrontTakesTheFirstSongsPlace() {
        val start = QueueShuffleOrder.startingWith(5, 3, Random(5))
        val played = order(start.cloneAndInsert(0, 1))
        val firstAt = start.order().indexOf(0)
        assertEquals(0, played[firstAt])
        assertEquals(1, played[firstAt + 1])
    }

    @Test fun aRemovalKeepsTheRestInOrder() {
        val start = QueueShuffleOrder.startingWith(6, 0, Random(11))
        val played = order(start.cloneAndRemove(2, 4))
        assertEquals(start.order().filter { it !in 2 until 4 }.map { if (it >= 4) it - 2 else it }, played)
    }

    @Test fun aMoveCarriesEachSongsPlaceWithIt() {
        val start = QueueShuffleOrder.startingWith(5, 1, Random(9))
        // Queue [a b c d e] becomes [a c d b e]: b (1) moved to index 3.
        val played = order(start.cloneAndMove(1, 2, 3))
        val renumber = mapOf(0 to 0, 1 to 3, 2 to 1, 3 to 2, 4 to 4)
        assertEquals(start.order().map { renumber.getValue(it) }, played)
    }

    @Test fun insertingIntoAnEmptyQueueShufflesIt() {
        val played = order(QueueShuffleOrder(Random(2)).cloneAndInsert(0, 6))
        assertEquals((0 until 6).toSet(), played.toSet())
        assertEquals(C.INDEX_UNSET, QueueShuffleOrder().cloneAndClear().getFirstIndex())
    }

    @Test fun previousWalksBackThroughTheSameOrder() {
        val shuffle = QueueShuffleOrder.startingWith(7, 6, Random(4))
        val forward = order(shuffle)
        val backward = mutableListOf<Int>()
        var i = shuffle.getLastIndex()
        while (i != C.INDEX_UNSET) { backward += i; i = shuffle.getPreviousIndex(i) }
        assertEquals(forward.reversed(), backward)
    }
}
