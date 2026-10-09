@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.datasource.DataSource
import java.io.Closeable
import java.io.IOException

/** SavedAudio implementation for verified migrated copies only. NOT SELECTED by OfflineStore yet.
 * Reserved/Copying/Verified/Uncertain and a new save's Closed are not published routes. No legacy or
 * live fallback is provided. The caller retains the legacy implementation for unmigrated originals
 * and still owes app-wide writer/removal/eviction exclusion and opt-in/recovery orchestration.
 *
 * At most six native instances/sixteen pins reside in this pool; the owner's process-wide budget
 * additionally counts other pools, opening/closing and quarantine. Catalog enumeration opens no
 * native cache. Close stops new admission and never force-closes a reader that still owns a pin.
 * Catalog/journal/owner lifetimes are borrowed; the coordinator closes them only after safe drain.
 */
internal class PartitionPublishedAudio(private val owner: PartitionNativeOwner,
    capacity: Int = PARTITION_NATIVE_INSTANCES) : SavedAudio, Closeable {
    private val pool = PartitionCacheLeases(owner::openReady, capacity = capacity)
    internal val resident: Int get() = pool.resident
    internal val active: Int get() = pool.active
    @Volatile private var stopped = false
    private fun requireOpen() { if (stopped) throw IOException("Published saved storage is stopped") }
    private fun ready(key: String): MigrationRecord {
        requireOpen()
        return owner.readyRoute(key) ?: throw IOException("Saved copy has no verified published partition")
    }
    private fun unchanged(expected: MigrationRecord) {
        if (owner.readyRoute(expected.ticket.allocation.key) != expected)
            throw IOException("Published saved-copy routing changed")
    }
    override val source: DataSource.Factory = PartitionSavedSource(pool, validation = { key ->
        val expected = ready(key)
        val check: () -> Unit = { unchanged(expected) }
        check
    })
    override fun inspect(key: String): SavedAudioState {
        val expected = ready(key)
        return pool.acquire(key).use { pin ->
            unchanged(expected)
            if (pin.cache.uid != expected.targetUid) throw IOException("Published saved-copy native identity changed")
            savedAudioState(pin.cache, key).also { unchanged(expected) }
        }
    }
    /** Routing existence only; actual inspection/source opening still verifies native files/UID. */
    override fun contains(key: String): Boolean {
        requireOpen()
        return owner.readyRoute(key) != null
    }
    override fun forEachKey(visit: (String) -> Boolean) {
        var after: String? = null
        while (true) {
            requireOpen()
            val page = owner.routePage(after)
            if (page.isEmpty()) return
            for (row in page) if (row.phase == MigrationPhase.Ready) {
                unchanged(row)
                val more = visit(row.ticket.allocation.key)
                requireOpen(); unchanged(row)
                if (!more) return
            }
            after = page.last().ticket.allocation.key
        }
    }
    override fun close() { stopped = true; pool.close() }
}
