package dev.avery.muon

import androidx.media3.exoplayer.offline.DownloadRequest
import java.util.UUID

/**
 * Process-local ownership of a move's exact Add and source Remove. Tagged commands are refused after
 * invalidation or restart; a same-ID completion alone grants nothing. An accepted source removal stays
 * pending until its matching manager callback, so another move/deletion cannot overtake it. Restart drops
 * receipts and the retained-index policy stops old unfinished operations, preserving available copies.
 * This orders supported commands, not arbitrary filesystem writers or a cache/index transaction.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DownloadMoveReceipts(private val capacity: Int = 128) {
    private enum class Stage { AddQueued, Added, RemoveQueued, Removing }
    class Receipt internal constructor(val from: Shelf, val to: Shelf, val request: DownloadRequest) {
        val token: String = UUID.randomUUID().toString()
        internal var cancelled = false
        private var stage = Stage.AddQueued
        internal var checkedEpoch = -1L
        internal var checkedLength = -1L
        val removalPending: Boolean get() = stage == Stage.RemoveQueued || stage == Stage.Removing
        internal fun added() = stage == Stage.Added
        internal fun addQueued() = stage == Stage.AddQueued
        internal fun removeQueued() = stage == Stage.RemoveQueued
        internal fun removing() = stage == Stage.Removing
        internal fun admitAdd() { stage = Stage.Added }
        internal fun queueRemove(epoch: Long, length: Long) { checkedEpoch = epoch; checkedLength = length; stage = Stage.RemoveQueued }
        internal fun admitRemove() { stage = Stage.Removing }
    }
    private val pending = HashMap<Pair<Shelf, String>, Receipt>()
    val hasPending: Boolean @Synchronized get() = pending.isNotEmpty()
    /** Once removal reaches Media3, cancellation cannot undo it: keep the barrier until acknowledgment. */
    val removalInFlight: Boolean @Synchronized get() = pending.values.any { it.removing() }

    @Synchronized fun remember(from: Shelf, to: Shelf, request: DownloadRequest): Boolean {
        val key = to to request.id
        if (key in pending || pending.size >= capacity) return false
        pending[key] = Receipt(from, to, request)
        return true
    }
    @Synchronized fun find(to: Shelf, id: String): Receipt? = pending[to to id]
    @Synchronized fun tagged(shelf: Shelf, id: String, token: String, adding: Boolean): Receipt? =
        pending.values.singleOrNull {
            it.token == token && it.request.id == id && (if (adding) it.to === shelf else it.from === shelf) &&
                !it.cancelled && (if (adding) it.addQueued() else it.removeQueued())
        }
    @Synchronized fun admitAdd(receipt: Receipt): Boolean {
        if (pending[receipt.to to receipt.request.id] !== receipt || receipt.cancelled || !receipt.addQueued()) return false
        receipt.admitAdd()
        return true
    }
    @Synchronized fun readyToCheck(receipt: Receipt): Boolean =
        pending[receipt.to to receipt.request.id] === receipt && !receipt.cancelled && receipt.added() &&
            pending.values.none { it.removalPending }
    @Synchronized fun ready(): List<Receipt> =
        if (pending.values.any { it.removalPending }) emptyList() else pending.values.filter { !it.cancelled && it.added() }

    @Synchronized fun queueRemoval(receipt: Receipt, epoch: Long, length: Long, send: () -> Unit): Boolean {
        if (!readyToCheck(receipt)) return false
        receipt.queueRemove(epoch, length)
        try { send() } catch (failure: Exception) { finish(receipt); throw failure }
        return true
    }
    @Synchronized fun admitRemoval(receipt: Receipt): Boolean {
        if (pending[receipt.to to receipt.request.id] !== receipt || receipt.cancelled || !receipt.removeQueued()) return false
        receipt.admitRemove()
        return true
    }
    @Synchronized fun acknowledgeRemoval(from: Shelf, request: DownloadRequest): Boolean {
        val receipt = pending.values.singleOrNull {
            it.from === from && it.request == request && it.removing()
        } ?: return false
        finish(receipt)
        return true
    }
    /** A service's earlier binding/availability gate refused delivery; release only a queued command. */
    @Synchronized fun refuseQueued(token: String) {
        pending.values.singleOrNull { it.token == token && (it.addQueued() || it.removeQueued()) }
            ?.let(::finish)
    }
    @Synchronized fun invalidate(id: String) {
        pending.values.filter { it.request.id == id }.toList().forEach {
            it.cancelled = true
            if (!it.removing()) pending.remove(it.to to id)
        }
    }
    @Synchronized fun invalidateAll() {
        pending.values.toList().forEach { invalidate(it.request.id) }
    }
    @Synchronized fun finish(receipt: Receipt) {
        if (pending[receipt.to to receipt.request.id] === receipt) pending.remove(receipt.to to receipt.request.id)
    }
    @Synchronized fun publish(receipt: Receipt, send: () -> Unit) {
        if (!receipt.cancelled && pending[receipt.to to receipt.request.id] === receipt) send()
    }
}
