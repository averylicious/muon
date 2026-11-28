package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LyricsPreferencesTest {
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lyrics-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    /** #797: nothing is sent to LRCLIB until the user turns it on. */
    @Test fun onlineLyricsAreOffUntilChosen() {
        assertEquals(false, LyricsPreferences(prefs).online)
        assertEquals(false, LyricsPreferences(prefs).hintDismissed)
    }

    @Test fun choicesSurviveARestart() {
        LyricsPreferences(prefs).chooseOnline(true)
        LyricsPreferences(prefs).dismissHint()
        val restarted = LyricsPreferences(prefs)
        assertEquals(true, restarted.online)
        assertEquals(true, restarted.hintDismissed)
    }
}
