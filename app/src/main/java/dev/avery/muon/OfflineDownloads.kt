package dev.avery.muon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
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
}
