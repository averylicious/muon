package dev.avery.muon

/**
 * Before keeping a worker batch or encoding tags: at most128 playable tracks and4MiB logical
 * preparation payload. Six bytes per UTF-16 unit conservatively covers held text plus UTF-8/Base64
 * record expansion;2KiB per entry covers numeric fields, URI/UUID and request overhead. The actual
 * preserved requests are independently checked against the request/Parcel budget before any send.
 * Oversized selections fail as a whole, never silently save a prefix or trim metadata.
 */
internal fun newSaveBatch(tracks: List<TauonTrack>): List<TauonTrack>? {
    val batch = ArrayList<TauonTrack>()
    var bytes = 0L
    for (track in tracks) {
        if (!track.playable) continue
        if (batch.size >= DOWNLOAD_COMMAND_COUNT) return null
        val text = track.title.length.toLong() + track.artist.length + track.album.length +
            track.albumArtist.length + track.trackNumber.length
        val cost = 6L * text + 2048L
        if (cost > MOVE_COMMAND_BYTES || cost > DOWNLOAD_REQUEST_BYTES - bytes) return null
        bytes += cost
        batch += track
    }
    return batch
}
