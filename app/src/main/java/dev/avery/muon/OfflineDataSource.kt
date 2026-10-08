package dev.avery.muon

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Plays each request from the shelf [route] names, once per open (#112, #213): a live song streams
 * through the phone's shelf, and a saved copy (a download on the phone or the SD card, or a played
 * copy) is read from its own shelf with no upstream at all, so a missing or partial byte fails rather
 * than reaching Tauon. A card found unavailable is not read (#179 S1). Only the card found when the
 * store was made is known: one inserted later is not used until Muon restarts.
 *
 * The chosen shelf is checked again before its source is made, and before every read (#179 containment;
 * docs/audits/2026-10-06-card-reader-containment.md). Once it is found unavailable, this open fails
 * every later read, even if the shelf seems to come back: the caller closes and opens again, and that
 * open routes afresh. Each check is a snapshot, not a lock: storage can still go between a check and
 * the read after it, and nothing here closes or releases a cache or manager.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OfflineDataSource(private val timing: SavedStartupTiming = SavedStartupTiming.DISABLED,
    private val route: (DataSpec) -> Pair<Shelf, DataSpec>) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null
    private var shelf: Shelf? = null
    // Sticky until close: a card that reappears is not read through the reader opened before it went.
    private var lost = false
    private var firstRead: SavedStartupTiming.Phase? = null

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        val saved = if (timing.enabled) SavedRef.parse(dataSpec.uri.toString()) else null
        val card = saved?.shelf == SavedShelf.Card
        val routeToken = if (saved != null) timing.begin(if (card) SavedStartupTiming.Phase.ROUTE_CARD else SavedStartupTiming.Phase.ROUTE_PHONE) else null
        val selected = try { route(dataSpec).also { timing.end(routeToken) } }
        catch (failure: Exception) { timing.end(routeToken, SavedStartupTiming.Outcome.FAILED); throw failure }
        val (shelf, spec) = selected
        val openToken = if (saved != null) timing.begin(if (card) SavedStartupTiming.Phase.OPEN_CARD else SavedStartupTiming.Phase.OPEN_PHONE) else null
        try {
            if (!shelf.available()) throw IOException("Storage became unavailable before opening")
            val factory = if (spec.uri.scheme == SAVED_SCHEME) shelf.savedSource else shelf.stream
            val source = factory.createDataSource().also { source -> listeners.forEach(source::addTransferListener) }
            active = source
            this.shelf = shelf
            lost = false
            firstRead = if (saved != null) {
                if (card) SavedStartupTiming.Phase.FIRST_READ_CARD else SavedStartupTiming.Phase.FIRST_READ_PHONE
            } else null
            return source.open(spec).also { timing.end(openToken) }
        } catch (failure: Exception) { timing.end(openToken, SavedStartupTiming.Outcome.FAILED); throw failure }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = checkNotNull(active) { "Read before open" }
        if (length == 0) return 0
        val token = firstRead?.let(timing::begin)
        firstRead = null // At most one read timing per open, including failures; no per-buffer log.
        try {
            if (lost || !checkNotNull(shelf).available()) {
                lost = true
                throw IOException("Storage became unavailable while reading")
            }
            return source.read(buffer, offset, length).also { timing.end(token) }
        } catch (failure: Exception) { timing.end(token, SavedStartupTiming.Outcome.FAILED); throw failure }
    }

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    // Always delegated, available or not: the source's own cleanup must still run.
    override fun close() {
        try { active?.close() } finally {
            active = null
            shelf = null
            lost = false
            firstRead = null
        }
    }
}
