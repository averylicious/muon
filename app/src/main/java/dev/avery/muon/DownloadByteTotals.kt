package dev.avery.muon

/**
 * Each finished download's size by id, and their total, kept up to date as each one changes rather than
 * summed again on every change (#253). One entry per id, whichever shelf it was recorded from.
 */
internal class DownloadByteTotals {
    private val sizes = HashMap<String, Long>()

    /** The sum of every recorded size; it wraps on overflow exactly as summing them all would. */
    var total = 0L
        private set

    /** Records [id]'s size, replacing any earlier one. */
    fun put(id: String, bytes: Long) {
        total += bytes - (sizes.put(id, bytes) ?: 0L)
    }

    /** Forgets [id]'s size; nothing happens for an id never recorded. */
    fun remove(id: String) {
        sizes.remove(id)?.let { total -= it }
    }
}
