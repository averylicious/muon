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

/**
 * Command admission while a move reads and writes the copies it protects (#230). A move takes it only when
 * both managers are initialized, idle and hold no queued, downloading, removing or restarting work (see
 * OfflineStore.move); while it is held, the download services turn every command that could change a
 * manager's downloads into a no-op before Media3 sees it, and Muon's own senders refuse with a notice.
 * The move releases it on the main thread in the same step that hands its copies over, after its last copy.
 *
 * This orders commands that reach Muon's services and senders. It is not a lock on SimpleCache, on
 * Media3's internal handler or downloader threads, or on work Media3 starts by itself, and it is not a
 * transaction: see docs/handoffs/2026-10-08-move-command-admission.md for what it covers.
 */
internal class MoveExclusion {
    private var holder = false

    /** Takes the exclusion if no move holds it; false means another move is still in flight. */
    @Synchronized fun tryAcquire(): Boolean = if (holder) false else { holder = true; true }

    @Synchronized fun release() { holder = false }

    val held: Boolean @Synchronized get() = holder
}
