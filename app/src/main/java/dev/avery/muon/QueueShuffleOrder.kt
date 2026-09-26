package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import java.util.Random

/**
 * The player's shuffled order, arranged the way a listener expects rather than Media3's default,
 * which places the chosen song, and any song added later, at a random point in the order:
 *
 * - a new queue starts with the song it was started from, and every other song follows, shuffled,
 *   so a tap never lands near the end of a shuffle with only a few songs left after it;
 * - a song inserted into the queue follows the song just before it, so *Play next* (inserted after
 *   the playing song) is played next with shuffle on too;
 * - a song appended to the queue goes to the end of the shuffle, as *Add to queue* promises;
 * - a move carries each song's place in the shuffle with it, and a removal leaves the rest in order.
 *
 * Turning shuffle on reshuffles from the playing song ([startingWith]); see [PlaybackService].
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class QueueShuffleOrder private constructor(private val shuffled: IntArray, private val random: Random) : ShuffleOrder {
    /** Where each queue index sits in [shuffled]. */
    private val place = IntArray(shuffled.size).also { place -> shuffled.forEachIndexed { at, index -> place[index] = at } }

    constructor(random: Random = Random()) : this(IntArray(0), random)

    /** The shuffled order, first to last, as queue indices. */
    fun order(): List<Int> = shuffled.toList()

    override fun getLength(): Int = shuffled.size
    override fun getNextIndex(index: Int): Int = place[index].let { if (it + 1 < shuffled.size) shuffled[it + 1] else C.INDEX_UNSET }
    override fun getPreviousIndex(index: Int): Int = place[index].let { if (it > 0) shuffled[it - 1] else C.INDEX_UNSET }
    override fun getLastIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.last()
    override fun getFirstIndex(): Int = if (shuffled.isEmpty()) C.INDEX_UNSET else shuffled.first()

    override fun cloneAndSet(insertionCount: Int, startIndex: Int): ShuffleOrder =
        startingWith(insertionCount, startIndex, random)

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (shuffled.isEmpty()) return startingWith(insertionCount, C.INDEX_UNSET, random)
        val added = IntArray(insertionCount) { insertionIndex + it }
        val at = when {
            insertionIndex >= shuffled.size -> shuffled.size
            insertionIndex == 0 -> place[0]
            else -> place[insertionIndex - 1] + 1
        }
        val kept = IntArray(shuffled.size) { shuffled[it].let { index -> if (index >= insertionIndex) index + insertionCount else index } }
        return QueueShuffleOrder(kept.copyOfRange(0, at) + added + kept.copyOfRange(at, kept.size), next())
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val removed = indexToExclusive - indexFrom
        val kept = shuffled.filter { it < indexFrom || it >= indexToExclusive }
            .map { if (it >= indexToExclusive) it - removed else it }
        return QueueShuffleOrder(kept.toIntArray(), next())
    }

    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int): ShuffleOrder {
        val moved = (indexFrom until indexToExclusive).toList()
        val queue = (0 until shuffled.size).filter { it !in indexFrom until indexToExclusive }.toMutableList()
        queue.addAll(newIndexFrom, moved)
        val renumbered = IntArray(shuffled.size).also { queue.forEachIndexed { now, before -> it[before] = now } }
        return QueueShuffleOrder(IntArray(shuffled.size) { renumbered[shuffled[it]] }, next())
    }

    override fun cloneAndClear(): ShuffleOrder = QueueShuffleOrder(IntArray(0), next())

    private fun next() = Random(random.nextLong())

    companion object {
        /** A fresh shuffle of [length] songs, with [startIndex] first when it is one of them. */
        fun startingWith(length: Int, startIndex: Int, random: Random = Random()): QueueShuffleOrder {
            val shuffled = IntArray(length)
            for (i in 0 until length) {
                val swap = random.nextInt(i + 1)
                shuffled[i] = shuffled[swap]
                shuffled[swap] = i
            }
            if (startIndex in 0 until length) {
                val at = shuffled.indexOf(startIndex)
                shuffled[at] = shuffled[0]
                shuffled[0] = startIndex
            }
            return QueueShuffleOrder(shuffled, Random(random.nextLong()))
        }
    }
}
