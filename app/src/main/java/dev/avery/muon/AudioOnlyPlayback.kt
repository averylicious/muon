package dev.avery.muon

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters

/** Muon displays its own song artwork, not Media3 video/image output. Keep those decoders
 * unselected while retaining audio preferences, tags and the caller's other track policy.
 * Extractors still read container/codec metadata; this is not their allocation/heap guard.
 */
internal fun audioOnlyPlaybackParameters(current: TrackSelectionParameters): TrackSelectionParameters =
    current.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
        .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
        .build()
