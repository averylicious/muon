package dev.avery.muon

import androidx.annotation.DrawableRes

/**
 * The 2.0 icon set. One rule, taken from the mockups: every line runs at 0, 45 or 90 degrees, with
 * flat terminals and square joins, on a 24dp grid at a single 2dp stroke weight. Transport glyphs
 * and arrowheads are filled so they hold their weight beside the large play button, and circles are
 * the deliberate exception, in search, album and artist.
 *
 * The geometry is the mockups' own: paths come from `docs/design/2.0/generate.py`, so an icon here
 * and the same icon on a mockup screen are the same path. `repeat-one` and `check` are not in the
 * generator and were drawn to the same rule.
 */
@DrawableRes
internal fun iconRes(kind: String): Int = when (kind) {
    "play" -> R.drawable.ic_play
    "pause" -> R.drawable.ic_pause
    "previous" -> R.drawable.ic_previous
    "next" -> R.drawable.ic_next
    "shuffle" -> R.drawable.ic_shuffle
    "repeat" -> R.drawable.ic_repeat
    "repeat-one" -> R.drawable.ic_repeat_one
    "volume" -> R.drawable.ic_volume
    "volume-low" -> R.drawable.ic_volume_low
    "search" -> R.drawable.ic_search
    "library" -> R.drawable.ic_library
    "music" -> R.drawable.ic_music
    "check" -> R.drawable.ic_check
    "back" -> R.drawable.ic_back
    "collapse" -> R.drawable.ic_collapse
    "close" -> R.drawable.ic_close
    "queue" -> R.drawable.ic_queue
    "lyrics" -> R.drawable.ic_lyrics
    "play-next" -> R.drawable.ic_play_next
    "add-queue" -> R.drawable.ic_add_queue
    "album" -> R.drawable.ic_album
    "artist" -> R.drawable.ic_artist
    "delete" -> R.drawable.ic_delete
    "drag-handle" -> R.drawable.ic_drag_handle
    else -> R.drawable.ic_settings
}

/**
 * Every kind the UI asks for, so a typo in a call site fails a test rather than drawing settings.
 *
 * Kinds are hyphenated, following `repeat-one`. The screens still to be built take their names from
 * this list rather than inventing new strings: `back`, `collapse`, `volume-low`, `queue` and
 * `lyrics` for the Now Playing overlay (#43); `play-next`, `add-queue`, `album` and `artist` for the
 * song actions sheet (#46); `delete` and `drag-handle` for the Queue screen (#47); `close` for
 * search (#48). Those kinds are mapped here but not yet drawn anywhere.
 */
internal val ICON_KINDS = listOf(
    "play", "pause", "previous", "next", "shuffle", "repeat", "repeat-one",
    "volume", "volume-low", "search", "library", "music", "check", "settings",
    "back", "collapse", "close", "queue", "lyrics",
    "play-next", "add-queue", "album", "artist", "delete", "drag-handle",
)
