@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.ForwardingTimeline

/**
 * The session's player (#738): controllers, the notification and the legacy platform session see
 * the selected song's queued metadata, not optional text read from the stream. Title, artist, album
 * and album artist come from Muon's queued item, which is already budgeted; unused stream text
 * (composer, description, author, …) is left blank, as the user chose for controls. Every other
 * state field, event, position, command, the timeline contents and native Tracks pass through unchanged,
 * and edits still reach the wrapped PlaybackQueuePlayer's guards. Embedded artwork and numeric
 * fields keep their existing values; the artwork array is shared, not copied. The ExoPlayer itself
 * (ReplayGain's Tracks, decoding, original tags) is untouched. This is a transport projection, not
 * a universal Binder or heap limit.
 */
internal class ControlMetadataPlayer(player: Player) : ForwardingSimpleBasePlayer(player) {
    // Cache one opaque view, not a per-item list or growing timeline map. Nullable cache fields are
    // safe even if a delegate causes an early state read during construction.
    private var sourceTimeline: Timeline? = null
    private var projectedTimeline: Timeline? = null

    override fun getState(): SimpleBasePlayer.State {
        val state = super.getState()
        if (sourceTimeline !== state.timeline || projectedTimeline == null) {
            sourceTimeline = state.timeline
            projectedTimeline = object : ForwardingTimeline(state.timeline) {}
        }
        // State.buildUpon restores per-item metadata for its own private PlaylistTimeline. Hide that
        // implementation behind the supported forwarding view so later one-shot-state resets retain
        // this explicit projection. Window/period UIDs, navigation/shuffle and Tracks still delegate.
        return state.buildUpon().setPlaylist(checkNotNull(projectedTimeline), state.currentTracks, controlMetadata(state)).build()
    }
}

private fun controlMetadata(state: SimpleBasePlayer.State): MediaMetadata {
    val timeline = state.timeline
    if (timeline.isEmpty) return MediaMetadata.EMPTY
    // State's own rule: an unset index means the first item.
    val index = state.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: 0
    val queued = timeline.getWindow(index, Timeline.Window()).mediaItem.mediaMetadata
    // Start from the raw metadata, not populate(): populate skips null fields and does not copy
    // author, so stream values would survive an overlay.
    return state.currentMetadata.buildUpon()
        .setTitle(queued.title).setArtist(queued.artist).setAlbumTitle(queued.albumTitle).setAlbumArtist(queued.albumArtist)
        .setDisplayTitle(null).setSubtitle(null).setDescription(null).setWriter(null).setAuthor(null)
        .setComposer(null).setConductor(null).setDiscSubtitle(null).setGenre(null).setCompilation(null).setStation(null)
        .setArtworkUri(queued.artworkUri).setExtras(queued.extras).setSupportedCommands(queued.supportedCommands)
        .build()
}
