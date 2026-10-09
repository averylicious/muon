@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata

/** Read boundary for one shelf. Callers receive one resource's state, never its native Cache/owner.
 * A future partition implementation must hold its reader lease for inspection and keep a source's
 * lease until actual close. It must enumerate published keys from disk, not open every partition.
 * This interface grants no write, completion, removal, migration or remote-identity authority.
 */
internal interface SavedAudio {
    val source: DataSource.Factory
    fun inspect(key: String): SavedAudioState
    fun contains(key: String): Boolean
    fun forEachKey(visit: (String) -> Boolean)
}

internal data class SavedAudioState(val coverage: SavedCoverage, val bytes: Long,
    val metadata: ContentMetadata)

/** Explicit legacy implementation. Its already-open native index and keys remain unbounded (#253).
 * CacheDataSource is read-only: missing bytes cannot reach Tauon or create replacement cache files.
 * The optional file factory is for real-source close/containment controls, not another upstream.
 */
internal class LegacySavedAudio(private val cache: Cache, files: DataSource.Factory? = null) : SavedAudio {
    override val source: DataSource.Factory = CacheDataSource.Factory().setCache(cache)
        .setUpstreamDataSourceFactory(null).setCacheWriteDataSinkFactory(null)
        .also { if (files != null) it.setCacheReadDataSourceFactory(files) }

    override fun inspect(key: String): SavedAudioState = savedAudioState(cache, key)
    override fun contains(key: String): Boolean = key in cache.keys
    override fun forEachKey(visit: (String) -> Boolean) {
        // Keep legacy inventory ordering; production played inventory already supplies its disk order.
        for (key in cache.keys.sorted()) if (!visit(key)) break
    }
}

/** Scalar coverage query; metadata is the immutable value returned by Media3, not an open reader. */
internal fun savedAudioState(cache: Cache, key: String): SavedAudioState {
    val bytes = cache.getCachedBytes(key, 0, Long.MAX_VALUE)
    check(bytes >= 0) { "Invalid cached byte total" }
    val metadata = cache.getContentMetadata(key)
    val length = ContentMetadata.getContentLength(metadata)
    val coverage = when {
        bytes == 0L -> SavedCoverage.Missing
        length == C.LENGTH_UNSET.toLong() || length <= 0 -> SavedCoverage.UnknownLength
        cache.isCached(key, 0, length) -> SavedCoverage.Full
        else -> SavedCoverage.Partial
    }
    return SavedAudioState(coverage, bytes, metadata)
}
