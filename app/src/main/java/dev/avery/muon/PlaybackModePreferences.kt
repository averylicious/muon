package dev.avery.muon

import android.content.SharedPreferences
import androidx.media3.common.Player

/**
 * Shuffle and repeat as the user last left them. Defaults are Media3's own: shuffle off, repeat off.
 * Only these two modes are kept — not the queue, its shuffled order, the track or the position — and
 * nothing here starts playback.
 */
internal data class PlaybackModes(val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF)

/** A stored repeat value if it is one Media3 defines, otherwise off. */
internal fun repeatModeFrom(stored: Int?): Int = when (stored) {
    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ONE
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ALL
    else -> Player.REPEAT_MODE_OFF
}

/** Where the modes are kept. Each mode is saved on its own, never both from one change. */
internal interface PlaybackModeStore {
    fun read(): PlaybackModes
    fun saveShuffle(enabled: Boolean)
    fun saveRepeat(mode: Int)
}

/** The part of the player that holds the two modes. */
internal interface PlaybackModeTarget {
    fun restore(modes: PlaybackModes)
    fun listen(onShuffle: (Boolean) -> Unit, onRepeat: (Int) -> Unit)
}

/**
 * Puts the stored modes on the player, and only then starts saving the player's changes.
 *
 * Restoring first means the restore itself is never mistaken for the user changing a mode, and the
 * service calls this before its media session exists, so no controller ever sees the defaults. Media3
 * may still deliver the restore's own change events once the listener is attached; those carry the
 * restored values, so saving them writes back what was already stored. Each event saves only the mode
 * it is about: a handler that wrote both could store one mode's default over the other's saved value.
 */
internal fun restoreAndPersistPlaybackModes(store: PlaybackModeStore, target: PlaybackModeTarget) {
    target.restore(store.read())
    target.listen(onShuffle = store::saveShuffle, onRepeat = store::saveRepeat)
}

/**
 * The modes in their own preferences file, alongside Muon's other small settings. Stable and Canary
 * are separate apps, so each keeps its own.
 *
 * A value of the wrong type — from an older build or a damaged file — reads as the default instead of
 * crashing the playback service. Saves use `apply()`, like the app's other settings: the change is in
 * memory at once and written to disk shortly after, off the main thread. A process killed within that
 * short window, such as by a force-stop immediately after tapping a mode, can lose that last change.
 */
internal class PlaybackModePreferences(private val prefs: SharedPreferences) : PlaybackModeStore {
    override fun read(): PlaybackModes = PlaybackModes(
        shuffle = orNull { prefs.getBoolean(KEY_SHUFFLE, false) } ?: false,
        repeat = repeatModeFrom(orNull { prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF) }),
    )

    override fun saveShuffle(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHUFFLE, enabled).apply()
    }

    override fun saveRepeat(mode: Int) {
        prefs.edit().putInt(KEY_REPEAT, repeatModeFrom(mode)).apply()
    }

    private inline fun <T> orNull(read: () -> T): T? = try { read() } catch (e: ClassCastException) { null }

    companion object {
        const val FILE = "playback"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
    }
}
