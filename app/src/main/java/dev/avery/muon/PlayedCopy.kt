package dev.avery.muon

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import java.io.IOException

internal class PlayedCopyTooLarge : IOException("Played song exceeds the selected cache limit")

/** Blocking opportunistic copy only; explicit downloads must never use this policy. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun copyPlayedWithinLimit(source: CacheDataSource, spec: DataSpec, limit: () -> Long) {
    val key = requireNotNull(spec.key)
    require(key.startsWith(PLAYED_PREFIX)) { "Only played copies may be budget-limited" }
    try {
        CacheWriter(source, spec, null) { length, cached, _ ->
            val budget = limit().coerceAtLeast(0)
            // Includes resumed bytes. Unknown-length streams are checked after each read; at most
            // one writer buffer can cross the budget before close commits and cleanup removes it.
            if (length > budget || cached > budget) throw PlayedCopyTooLarge()
        }.cache()
    } catch (failure: PlayedCopyTooLarge) {
        // CacheWriter closes its data source on callback failure before this removes committed spans.
        source.cache.removeResource(key)
        throw failure
    }
}
