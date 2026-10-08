@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.exoplayer.offline.DownloadRequest

/** Logical retained request payload budget, not a byte-exact heap or Binder measurement. */
internal const val MOVE_METADATA_BYTES = 4L * 1024 * 1024

/** No Parcel/encoded metadata copy: count raw arrays, UTF-16 fields, stream keys and fixed overhead. */
internal fun moveRequestBytes(request: DownloadRequest): Long =
    request.data.size.toLong() + (request.keySetId?.size ?: 0).toLong() +
        2L * (request.id.length.toLong() + request.uri.toString().length +
            (request.mimeType?.length ?: 0) + (request.customCacheKey?.length ?: 0)) +
        64L * request.streamKeys.size + 256L // Logical allowance for request/range/list bookkeeping.

internal class DownloadMoveBudget(private val maximum: Long = MOVE_METADATA_BYTES) {
    init { require(maximum >= 0) }
    private var used = 0L
    fun fits(request: DownloadRequest): Boolean = moveRequestBytes(request) <= maximum - used
    fun commit(request: DownloadRequest) {
        val bytes = moveRequestBytes(request)
        check(bytes <= maximum - used)
        used += bytes
    }
    fun fitsAlone(request: DownloadRequest): Boolean = moveRequestBytes(request) <= maximum
}
