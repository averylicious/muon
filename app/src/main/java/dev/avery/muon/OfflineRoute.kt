package dev.avery.muon

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import java.io.IOException

/**
 * Which shelf serves a player request, and the request to make of it (#213). A live Tauon song always
 * streams: nothing establishes that a copy saved under its number is the song Tauon has now, so a
 * download or played copy is never substituted for it, online or offline. A saved copy's handle reads
 * that copy alone, from its own shelf and key, cache-only (see [OfflineDataSource]); an unknown handle,
 * or one on a card that is not there, fails rather than falling back to anything else.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun routeOfflineRequest(spec: DataSpec, phone: Shelf, card: Shelf?): Pair<Shelf, DataSpec> {
    if (spec.uri.scheme != SAVED_SCHEME) return phone to spec
    val ref = SavedRef.parse(spec.uri.toString()) ?: throw IOException("Not a saved copy Muon made")
    val shelf = when (ref.shelf) {
        SavedShelf.Phone -> phone
        SavedShelf.Card -> card ?: throw IOException("The SD card this copy is on isn't here")
    }
    return shelf to spec.buildUpon().setKey(ref.key).build()
}
