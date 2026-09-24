package dev.avery.muon

import android.content.SharedPreferences
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModePreferencesTest {
    /** In-memory preferences whose getters cast as Android's do, so a wrong type throws. */
    private class MemoryPrefs(vararg entries: Pair<String, Any?>) : SharedPreferences {
        val values = mutableMapOf<String?, Any?>(*entries)
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()
        override fun getString(key: String?, defValue: String?) = (values[key] as String?) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = (values[key] as Int?) ?: defValue
        override fun getLong(key: String?, defValue: Long) = (values[key] as Long?) ?: defValue
        override fun getFloat(key: String?, defValue: Float) = (values[key] as Float?) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = (values[key] as Boolean?) ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?) = also { values[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = also { values[key] = value }
            override fun putLong(key: String?, value: Long) = also { values[key] = value }
            override fun putFloat(key: String?, value: Float) = also { values[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = also { values[key] = value }
            override fun remove(key: String?) = also { values.remove(key) }
            override fun clear() = also { values.clear() }
            override fun commit() = true
            override fun apply() {}
        }
    }

    /**
     * A player holding the two modes. Its change events reach whatever listener is attached, either at
     * once or later, as Media3 may deliver them after the call that caused them.
     */
    private class FakePlayer(private val deliverLater: Boolean = false) : PlaybackModeTarget {
        var shuffle = false
        var repeat = Player.REPEAT_MODE_OFF
        val calls = mutableListOf<String>()
        private var onShuffle: ((Boolean) -> Unit)? = null
        private var onRepeat: ((Int) -> Unit)? = null
        private val pending = mutableListOf<() -> Unit>()

        override fun restore(modes: PlaybackModes) {
            calls += "restore"
            changeShuffle(modes.shuffle)
            changeRepeat(modes.repeat)
        }

        override fun listen(onShuffle: (Boolean) -> Unit, onRepeat: (Int) -> Unit) {
            calls += "listen"
            this.onShuffle = onShuffle
            this.onRepeat = onRepeat
        }

        fun changeShuffle(enabled: Boolean) {
            if (enabled == shuffle) return
            shuffle = enabled
            event { onShuffle?.invoke(enabled) }
        }

        fun changeRepeat(mode: Int) {
            if (mode == repeat) return
            repeat = mode
            event { onRepeat?.invoke(mode) }
        }

        fun deliver() {
            val due = pending.toList()
            pending.clear()
            due.forEach { it() }
        }

        private fun event(send: () -> Unit) {
            if (deliverLater) pending += send else send()
        }
    }

    private fun stored(prefs: MemoryPrefs) = PlaybackModePreferences(prefs).read()

    @Test fun aFreshInstallUsesMedia3sDefaults() {
        assertEquals(PlaybackModes(shuffle = false, repeat = Player.REPEAT_MODE_OFF), stored(MemoryPrefs()))
    }

    @Test fun eachShuffleValueAndRepeatModeSurvivesARestart() {
        listOf(true, false).forEach { shuffle ->
            listOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL).forEach { repeat ->
                val prefs = MemoryPrefs()
                PlaybackModePreferences(prefs).apply { saveShuffle(shuffle); saveRepeat(repeat) }
                // A new service reads the same file afresh.
                assertEquals(PlaybackModes(shuffle, repeat), stored(prefs))
            }
        }
    }

    @Test fun anUnknownStoredRepeatValueFallsBackToOff() {
        listOf(3, 7, -1, Int.MAX_VALUE).forEach {
            assertEquals(Player.REPEAT_MODE_OFF, stored(MemoryPrefs("repeat" to it)).repeat)
        }
        assertEquals(Player.REPEAT_MODE_OFF, repeatModeFrom(null))
    }

    @Test fun valuesOfTheWrongTypeReadAsDefaultsInsteadOfCrashing() {
        val prefs = MemoryPrefs("shuffle" to "yes", "repeat" to "all")
        assertEquals(PlaybackModes(), stored(prefs))
    }

    @Test fun theStoredModesAreOnThePlayerBeforeAnyChangeIsSaved() {
        val prefs = MemoryPrefs("shuffle" to true, "repeat" to Player.REPEAT_MODE_ALL)
        val player = FakePlayer()
        restoreAndPersistPlaybackModes(PlaybackModePreferences(prefs), player)
        assertEquals(listOf("restore", "listen"), player.calls)
        assertEquals(true, player.shuffle)
        assertEquals(Player.REPEAT_MODE_ALL, player.repeat)
        // The restore's own changes happened before anything listened, so nothing was rewritten.
        assertEquals(PlaybackModes(true, Player.REPEAT_MODE_ALL), stored(prefs))
    }

    @Test fun restoreEventsDeliveredLateWriteBackOnlyWhatWasStored() {
        val prefs = MemoryPrefs("shuffle" to true, "repeat" to Player.REPEAT_MODE_ONE)
        val player = FakePlayer(deliverLater = true)
        restoreAndPersistPlaybackModes(PlaybackModePreferences(prefs), player)
        player.deliver()
        assertEquals(PlaybackModes(true, Player.REPEAT_MODE_ONE), stored(prefs))
    }

    @Test fun changingOneModeNeverOverwritesTheOther() {
        val prefs = MemoryPrefs("shuffle" to false, "repeat" to Player.REPEAT_MODE_ONE)
        val player = FakePlayer()
        restoreAndPersistPlaybackModes(PlaybackModePreferences(prefs), player)
        player.changeShuffle(true)
        assertEquals(PlaybackModes(true, Player.REPEAT_MODE_ONE), stored(prefs))
        player.changeRepeat(Player.REPEAT_MODE_ALL)
        assertEquals(PlaybackModes(true, Player.REPEAT_MODE_ALL), stored(prefs))
    }

    @Test fun changesAfterRestoringAreSavedAsTheyHappen() {
        val prefs = MemoryPrefs()
        val player = FakePlayer()
        restoreAndPersistPlaybackModes(PlaybackModePreferences(prefs), player)
        player.changeRepeat(Player.REPEAT_MODE_ONE)
        player.changeShuffle(true)
        // Saved at the change, not at some later shutdown.
        assertEquals(PlaybackModes(true, Player.REPEAT_MODE_ONE), stored(prefs))
        player.changeShuffle(false)
        player.changeRepeat(Player.REPEAT_MODE_OFF)
        assertEquals(PlaybackModes(false, Player.REPEAT_MODE_OFF), stored(prefs))
    }
}
