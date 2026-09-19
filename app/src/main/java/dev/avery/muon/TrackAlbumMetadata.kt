package dev.avery.muon

/** Optional Tauon tags must be strings; do not turn JSON objects/null into display text. */
internal fun albumArtistTag(value: Any?, trackArtist: String): String =
    (value as? String)?.trim().orEmpty().ifBlank { trackArtist }

/** Preserve tag notation such as "3/12" or "B2"; ordering is a separate library decision. */
internal fun trackNumberTag(value: Any?): String = (value as? String)?.trim().orEmpty()
