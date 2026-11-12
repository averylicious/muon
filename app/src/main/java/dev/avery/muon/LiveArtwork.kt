package dev.avery.muon

import java.nio.ByteBuffer
import java.security.MessageDigest

private const val ARTWORK_REVISION = "#muon-artwork-v1="

/**
 * Media3's session and notification caches key URI artwork by URI alone. Tauon may reuse an ID
 * after rebuilding its library, so give changed display metadata a new, stable cache address.
 * The fragment is local to the client: HTTP still requests the ordinary Tauon picture path.
 * This is cover invalidation, never proof that audio bytes or saved copies are the same song.
 */
internal fun TauonTrack.liveArtworkUrl(endpoint: ServerEndpoint): String {
    val tagUnits = title.length + artist.length + album.length
    val input = ByteBuffer.allocate(1 + 3 * Int.SIZE_BYTES + tagUnits * Char.SIZE_BYTES)
    // Version the digest input. Keep the marker so existing queued URIs still canonicalize.
    input.put(2)
    for (field in listOf(title, artist, album)) {
        input.putInt(field.length)
        // Charset encoding can replace distinct unpaired surrogates with the same bytes.
        for (unit in field) input.putChar(unit)
    }
    val revision = buildString(64) {
        for (byte in MessageDigest.getInstance("SHA-256").digest(input.array())) {
            append("0123456789abcdef"[(byte.toInt() ushr 4) and 15])
            append("0123456789abcdef"[byte.toInt() and 15])
        }
    }
    return endpoint.url("/api1/pic/medium/$id") + ARTWORK_REVISION + revision
}

/** Compose keeps its existing identity-aware memory/disk keys; do not duplicate its library map. */
internal fun canonicalArtworkUrl(url: String): String {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return url
    val marker = url.indexOf(ARTWORK_REVISION)
    if (marker < 0 || url.indexOf('#') != marker) return url
    val start = marker + ARTWORK_REVISION.length
    return if (url.length - start == 64 && url.substring(start).all { it in '0'..'9' || it in 'a'..'f' })
        url.substring(0, marker) else url
}
