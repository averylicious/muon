package dev.avery.muon

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Plays each request from the shelf that holds it (#112): a download on the phone or the SD card, a
 * played-song copy, or else the stream. [route] names the shelf and the request to make of it, once per
 * open, so a card found unavailable then is not read (#179 S1). Only the card found when the store was
 * made is known: one inserted later is not used until Muon restarts.
 *
 * The chosen shelf is checked again before its source is made, and before every read (#179 containment;
 * docs/audits/2026-10-06-card-reader-containment.md). Once it is found unavailable, this open fails
 * every later read, even if the shelf seems to come back: the caller closes and opens again, and that
 * open routes afresh. Each check is a snapshot, not a lock: storage can still go between a check and
 * the read after it, and nothing here closes or releases a cache or manager.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OfflineDataSource(private val route: (DataSpec) -> Pair<Shelf, DataSpec>) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null
    private var shelf: Shelf? = null
    // Sticky until close: a card that reappears is not read through the reader opened before it went.
    private var lost = false

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        val (shelf, spec) = route(dataSpec)
        // The card can go between the route's decision and here; nothing has been made or opened yet.
        if (!shelf.available()) throw IOException("Storage became unavailable before opening")
        val source = shelf.source.createDataSource().also { source -> listeners.forEach(source::addTransferListener) }
        active = source
        this.shelf = shelf
        lost = false
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = checkNotNull(active) { "Read before open" }
        // Media3 DataReader requires a zero-length read to return zero without I/O.
        if (length == 0) return 0
        if (lost || !checkNotNull(shelf).available()) {
            lost = true
            throw IOException("Storage became unavailable while reading")
        }
        return source.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    // Always delegated, available or not: the source's own cleanup must still run.
    override fun close() {
        try { active?.close() } finally {
            active = null
            shelf = null
            lost = false
        }
    }
}
