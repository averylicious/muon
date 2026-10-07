package dev.avery.muon

import java.io.IOException

/** Incoming track record budget, leaving room for its duplicated text in Media3's IPC item. */
internal const val TRACK_METADATA_MAX_BYTES = 16 * 1024

/**
 * Refuse oversized incoming tags before they enter the library or a queue/download request.
 * Never trim tags, rewrite retained records or claim this bounds all Binder/heap usage.
 * The character preflight keeps encoding itself small even when a wire-capped field is huge.
 */
internal fun requireTrackMetadataBudget(track: TauonTrack) {
    fun tooLarge(): Nothing = throw IOException(
        "Track ${track.id} metadata is too large for Muon (16 KiB limit). Check its tags, then retry.")
    var remaining = TRACK_METADATA_MAX_BYTES
    for (field in listOf(track.title, track.artist, track.album, track.albumArtist, track.trackNumber)) {
        if (field.length > remaining) tooLarge()
        remaining -= field.length
    }
    if (encodeSong(track).size > TRACK_METADATA_MAX_BYTES) tooLarge()
    // Credit formatting may expand semicolons into comma-space separators. Bound the displayed
    // fields as well as the encoded record, rather than assuming their byte lengths are equal.
    val displayed = listOf(track.title, displayCredits(track.artist), track.album, displayCredits(track.albumArtist))
    if (displayed.sumOf { it.toByteArray(Charsets.UTF_8).size } > TRACK_METADATA_MAX_BYTES) tooLarge()
}
