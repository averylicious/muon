package dev.avery.muon

import androidx.media3.common.Player

@Player.RepeatMode
internal fun nextRepeatMode(@Player.RepeatMode current: Int): Int = when (current) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

internal fun repeatModeName(@Player.RepeatMode mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ALL -> "All"
    Player.REPEAT_MODE_ONE -> "One"
    else -> "Off"
}

/** Repeat-one needs its own glyph now that the mode is not spelled out in a button label. */
internal fun repeatModeIcon(@Player.RepeatMode mode: Int): String =
    if (mode == Player.REPEAT_MODE_ONE) "repeat-one" else "repeat"
