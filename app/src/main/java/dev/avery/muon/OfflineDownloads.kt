package dev.avery.muon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Downloads for offline listening (#112), as the UI sees them. A download is keyed exactly as the
 * player keys a song, "origin/track id", so a row, the queue and the store all name the same song.
 * What is kept is Tauon's Opus transcode (`/api1/fileopus`, 84 kbps, fixed by Tauon for now); the
 * lossless originals are streamed and never stored.
 */
internal enum class DownloadMark { Queued, Downloading, Done }

internal fun downloadId(origin: String, trackId: Long): String = "$origin/$trackId"

/**
 * The download that a stream request for [scheme]://[authority][path] would play instead, or null
 * when the path is not a track's original file or the address is not a trusted server. Only the
 * original-file path is ever redirected, so artwork and everything else is left alone.
 */
internal fun downloadForStream(scheme: String?, authority: String?, path: String?): Pair<String, String>? {
    val number = Regex("/api1/file/([0-9]+)").matchEntire(path.orEmpty())?.groupValues?.get(1) ?: return null
    val origin = runCatching { ServerEndpoint.parse("$scheme://$authority").origin }.getOrNull() ?: return null
    return "$origin/$number" to "$origin/api1/fileopus/$number"
}

/** Tauon's Opus is 84 kbps: about 10.5 kB for every second of music. */
internal const val OPUS_BYTES_PER_SECOND = 84_000L / 8

/** Roughly how much downloading these songs will take; songs of unknown length count for nothing. */
internal fun downloadEstimate(tracks: List<TauonTrack>): Long =
    tracks.sumOf { it.durationMs.coerceAtLeast(0) } * OPUS_BYTES_PER_SECOND / 1000

/** "340 MB", "1.1 GB", "about 900 kB": a size to read at a glance, in decimal units as Android shows them. */
internal fun formatBytes(bytes: Long): String = when {
    // Whole gigabytes read as "2 GB", as limits are offered.
    bytes >= 1_000_000_000 && bytes % 1_000_000_000 == 0L -> "${bytes / 1_000_000_000} GB"
    bytes >= 1_000_000_000 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000 -> "${(bytes + 500_000) / 1_000_000} MB"
    else -> "${(bytes + 500) / 1000} kB"
}

/** How far a page's downloads have got: [done] of [wanted] songs, [pending] still to come. */
internal data class DownloadProgress(val wanted: Int, val done: Int, val pending: Int) {
    val all: Boolean get() = wanted > 0 && done == wanted
}

internal fun downloadProgress(ids: List<String>, marks: Map<String, DownloadMark>): DownloadProgress {
    var done = 0
    var pending = 0
    ids.forEach { when (marks[it]) { DownloadMark.Done -> done++; null -> Unit; else -> pending++ } }
    return DownloadProgress(ids.size, done, pending)
}

/**
 * Every download's state, for Compose to read. Written only on the main thread, by the store's
 * listener; state transitions only, never byte-by-byte progress, so rows do not recompose while a
 * song downloads.
 */
internal object DownloadMarks {
    val marks = mutableStateMapOf<String, DownloadMark>()
    /** Bytes the finished downloads take. */
    var bytes by mutableLongStateOf(0L)
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

/** The key the offline library files its songs under, in place of a playlist. */
internal const val OFFLINE_LIBRARY = "offline"

/** Where a song's record travels: in its media item's extras, and in its played-song copy's metadata. */
internal const val SONG_EXTRA = "muon.song"
internal const val SONG_METADATA = "muon-song"
