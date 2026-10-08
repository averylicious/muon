package dev.avery.muon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Saved copies for offline listening (#112), as the UI sees them, keyed by each copy's request ID. What
 * is kept is Tauon's Opus transcode (`/api1/fileopus`, 84 kbps, fixed by Tauon for now); the lossless
 * originals are streamed and never stored. A live song is never matched to a copy by its number (#213).
 */
internal enum class DownloadMark { Queued, Downloading, Done }

/** A live song's media ID, "origin/track id"; older downloads used the same text as their key. */
internal fun downloadId(origin: String, trackId: Long): String = "$origin/$trackId"

/** Tauon's Opus is 84 kbps: about 10.5 kB for every second of music. */
internal const val OPUS_BYTES_PER_SECOND = 84_000L / 8

/** Roughly how much downloading these songs will take; songs of unknown length count for nothing. */
internal fun downloadEstimate(tracks: List<TauonTrack>): Long {
    var millis = 0L
    for (track in tracks) {
        val duration = track.durationMs.coerceAtLeast(0)
        if (duration > Long.MAX_VALUE - millis) return Long.MAX_VALUE
        millis += duration
    }
    // Scale whole seconds before the remainder; multiplying milliseconds first can wrap even
    // when the final byte estimate fits. Saturate estimates that cannot fit in a Long.
    val seconds = millis / 1000
    if (seconds > Long.MAX_VALUE / OPUS_BYTES_PER_SECOND) return Long.MAX_VALUE
    val whole = seconds * OPUS_BYTES_PER_SECOND
    val remainder = millis % 1000 * OPUS_BYTES_PER_SECOND / 1000
    return if (remainder > Long.MAX_VALUE - whole) Long.MAX_VALUE else whole + remainder
}

/** "340 MB", "1.1 GB", "about 900 kB": a size to read at a glance, in decimal units as Android shows them. */
internal fun formatBytes(bytes: Long): String = when {
    // Whole gigabytes read as "2 GB", as limits are offered.
    bytes >= 1_000_000_000 && bytes % 1_000_000_000 == 0L -> "${bytes / 1_000_000_000} GB"
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
    else -> "${(bytes + 500) / 1000} kB"
}

/**
 * Every download's state, for Compose to read. Written only on the main thread, by the store's
 * listener; state transitions only, never byte-by-byte progress, so rows do not recompose while a
 * song downloads.
 */
internal object DownloadMarks {
    val marks = mutableStateMapOf<String, DownloadMark>()
    /** State/removal signal without copying every mark into a Compose effect key. */
    var revision by mutableLongStateOf(0L)
    /** Bytes the finished downloads take. */
    var bytes by mutableLongStateOf(0L)
    /** A derived tally failure hides stale sizes; original audio/index records remain available. */
    var bytesKnown by mutableStateOf(true)
    /** Songs moved so far and how many are moving, while downloads move between phone and card. */
    var moving by mutableStateOf<Pair<Int, Int>?>(null)
}

/**
 * Saved song metadata for the offline library. Ordinary records retain the original NUL-delimited
 * format. If a tag contains NUL, version two encodes every textual field as UTF-8 Base64 so separators
 * remain unambiguous. Audio/cache IDs are unchanged; old stored records are never rewritten.
 */
private const val SONG_RECORD = "muon-song-1"
private const val ESCAPED_SONG_RECORD = "muon-song-2"

internal fun encodeSong(track: TauonTrack): ByteArray {
    val text = listOf(track.title, track.artist, track.album, track.albumArtist, track.trackNumber)
    val escaped = text.any { '\u0000' in it }
    val saved = if (escaped) text.map { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) } else text
    return listOf(if (escaped) ESCAPED_SONG_RECORD else SONG_RECORD, track.id.toString(),
        saved[0], saved[1], saved[2], saved[3], track.durationMs.toString(), saved[4])
        .joinToString("\u0000").toByteArray(Charsets.UTF_8)
}

/** Unknown, ambiguous or malformed records are left out without deleting their retained bytes. */
internal fun decodeSong(data: ByteArray): TauonTrack? {
    val fields = String(data, Charsets.UTF_8).split('\u0000')
    if (fields.size != 8 || fields[0] !in listOf(SONG_RECORD, ESCAPED_SONG_RECORD)) return null
    val id = fields[1].toLongOrNull() ?: return null
    val text = listOf(fields[2], fields[3], fields[4], fields[5], fields[7])
    val restored = if (fields[0] == ESCAPED_SONG_RECORD) {
        try { text.map { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) } }
        catch (_: IllegalArgumentException) { return null }
    } else text
    return TauonTrack(id, restored[0], restored[1], restored[2], fields[6].toLongOrNull() ?: 0L, playable = true,
        hasLyrics = false, albumArtist = restored[3], trackNumber = restored[4])
}

/** Where a song's record travels: in its media item's extras, and in its played-song copy's metadata. */
internal const val SONG_EXTRA = "muon.song"
internal const val SONG_METADATA = "muon-song"
