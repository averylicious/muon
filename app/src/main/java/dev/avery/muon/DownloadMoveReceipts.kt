package dev.avery.muon

import androidx.media3.exoplayer.offline.DownloadRequest

/**
 * Process-local evidence that this exact copy was published by a byte-checked user move. A completed
 * legacy download with the same ID on another shelf is not evidence of a move. Restart drops receipts
 * and keeps both copies. This registry orders command invalidation, not index/cache/file transactions.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class DownloadMoveReceipts(private val capacity: Int = 128) {
    class Receipt internal constructor(val from: Shelf, val to: Shelf, val request: DownloadRequest) {
        internal var cancelled = false
    }
    private val pending = HashMap<Pair<Shelf, String>, Receipt>()

    @Synchronized fun remember(from: Shelf, to: Shelf, request: DownloadRequest): Boolean {
        val key = to to request.id
        if (key in pending || pending.size >= capacity) return false
        pending[key] = Receipt(from, to, request)
        return true
    }
    @Synchronized fun find(to: Shelf, id: String): Receipt? = pending[to to id]
    @Synchronized fun invalidate(id: String) {
        pending.values.filter { it.request.id == id }.forEach {
            it.cancelled = true
            pending.remove(it.to to id)
        }
    }
    @Synchronized fun invalidateAll() {
        pending.values.forEach { it.cancelled = true }
        pending.clear()
    }
    @Synchronized fun finish(receipt: Receipt) {
        if (pending[receipt.to to receipt.request.id] === receipt)
            pending.remove(receipt.to to receipt.request.id)
    }
    @Synchronized fun publish(receipt: Receipt, send: () -> Unit) {
        if (!receipt.cancelled && pending[receipt.to to receipt.request.id] === receipt) send()
    }
}
