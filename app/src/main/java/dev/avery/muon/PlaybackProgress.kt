package dev.avery.muon

/** How often the UI samples the player's position while it is playing. */
internal const val POSITION_TICK_MS = 250L

/** Fraction of the track that has played, for progress bars. */
internal fun progressFraction(position: Long, duration: Long): Float =
    if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
