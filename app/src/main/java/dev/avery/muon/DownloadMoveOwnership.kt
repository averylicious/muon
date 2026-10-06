package dev.avery.muon

/**
 * Each move is registered when requested, before its worker can snapshot downloads. Removals
 * invalidate publication by all older in-flight moves; a later explicit move gets fresh ownership.
 * Finished batches leave no persistent tombstone or per-song preference/state.
 */
internal class DownloadMoveOwnership {
    class Batch internal constructor() {
        internal var cancelled = false
        internal val removed = HashSet<String>()
    }
    private val batches = HashSet<Batch>()

    @Synchronized fun begin(): Batch = Batch().also(batches::add)
    @Synchronized fun finish(batch: Batch) { batches.remove(batch) }
    @Synchronized fun remove(ids: List<String>) { batches.forEach { it.removed.addAll(ids) } }
    @Synchronized fun removeAll() { batches.forEach { it.cancelled = true } }
    @Synchronized fun permits(batch: Batch, id: String): Boolean =
        batch in batches && !batch.cancelled && id !in batch.removed

    /** Keep invalidation and the final command in one ordering boundary, including cross-thread callers. */
    @Synchronized fun publish(batch: Batch, id: String, action: () -> Unit) {
        if (permits(batch, id)) action()
    }
}
