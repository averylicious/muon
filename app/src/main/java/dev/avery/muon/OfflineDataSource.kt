package dev.avery.muon

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Plays each request from the shelf that holds it (#112): a download on the phone or the SD card, a
 * played-song copy, or else the stream. [route] names the shelf and the request to make of it, once per
 * open, so a card found unavailable then is not read (#179 S1). Only the card found when the store was
 * made is known: one inserted later is not used until Muon restarts, and a card removed after an open
 * is not noticed until the next one.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OfflineDataSource(private val route: (DataSpec) -> Pair<Shelf, DataSpec>) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }

    override fun open(dataSpec: DataSpec): Long {
        val (shelf, spec) = route(dataSpec)
        val source = shelf.source.createDataSource().also { source -> listeners.forEach(source::addTransferListener) }
        active = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active) { "Read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try { active?.close() } finally { active = null }
    }
}
