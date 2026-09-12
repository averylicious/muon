package dev.avery.muon

import androidx.annotation.DrawableRes

/**
 * One drawn-to-a-grid icon set replaces the hand-drawn Canvas glyphs, which each carried their own
 * stroke weight and construction. Every icon is 24dp on the same grid with a 2dp round-capped
 * stroke; transport glyphs are filled so they hold their weight next to the large play button.
 */
@DrawableRes
internal fun iconRes(kind: String): Int = when (kind) {
    "play" -> R.drawable.ic_play
    "pause" -> R.drawable.ic_pause
    "previous" -> R.drawable.ic_previous
    "next" -> R.drawable.ic_next
    "search" -> R.drawable.ic_search
    "library" -> R.drawable.ic_library
    "music" -> R.drawable.ic_music
    "shuffle" -> R.drawable.ic_shuffle
    "repeat" -> R.drawable.ic_repeat
    "repeat-one" -> R.drawable.ic_repeat_one
    "volume" -> R.drawable.ic_volume
    "check" -> R.drawable.ic_check
    else -> R.drawable.ic_settings
}

/** Every kind the UI asks for, so a typo in a call site fails a test rather than drawing settings. */
internal val ICON_KINDS = listOf(
    "play", "pause", "previous", "next", "search", "library", "music",
    "shuffle", "repeat", "repeat-one", "volume", "check", "settings",
)
