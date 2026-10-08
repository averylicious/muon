@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.exoplayer.offline.DownloadRequest
import android.os.Parcel

/** Logical retained request payload budget, not a byte-exact heap or Binder measurement. */
internal const val MOVE_METADATA_BYTES = 4L * 1024 * 1024
internal const val MOVE_COMMAND_BYTES = 768L * 1024
private const val MOVE_COMMAND_ENVELOPE_BYTES = 4L * 1024

/**
 * Refuse huge raw requests before allocating a Parcel, then measure the preserved request's actual
 * parcelled size with room for Muon's fixed service/receipt envelope. Android's shared Binder buffer
 * can still be busy: this is conservative admission, not guaranteed service delivery.
 */
internal fun moveCommandFits(request: DownloadRequest, maximum: Long = MOVE_COMMAND_BYTES): Boolean {
    require(maximum >= 0)
    if (moveRequestBytes(request) > maximum || maximum < MOVE_COMMAND_ENVELOPE_BYTES) return false
    val parcel = Parcel.obtain()
    return try {
        request.writeToParcel(parcel, 0)
        parcel.dataSize().toLong() <= maximum - MOVE_COMMAND_ENVELOPE_BYTES
    } finally { parcel.recycle() }
}

/** No Parcel/encoded metadata copy: count raw arrays, UTF-16 fields, stream keys and fixed overhead. */
internal fun moveRequestBytes(request: DownloadRequest): Long =
    request.data.size.toLong() + (request.keySetId?.size ?: 0).toLong() +
        2L * (request.id.length.toLong() + request.uri.toString().length +
            (request.mimeType?.length ?: 0) + (request.customCacheKey?.length ?: 0)) +
        64L * request.streamKeys.size + 256L // Logical allowance for request/range/list bookkeeping.

internal class DownloadMoveBudget(private val maximum: Long = MOVE_METADATA_BYTES) {
    init { require(maximum >= 0) }
    private var used = 0L
    fun fits(request: DownloadRequest): Boolean =
        moveRequestBytes(request) <= maximum - used && moveCommandFits(request)
    fun commit(request: DownloadRequest) {
        val bytes = moveRequestBytes(request)
        check(bytes <= maximum - used && moveCommandFits(request))
        used += bytes
    }
    fun fitsAlone(request: DownloadRequest): Boolean =
        moveRequestBytes(request) <= maximum && moveCommandFits(request)
}
