package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Disposable preferences: persistent settings/fallback contract, not tagged playback or audio QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayGainSettingsTest {
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("loudness-settings-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test fun freshSettingsAreOffAndUnknownTracksStayAtUnityUntilAGainExists() {
        val settings = ReplayGainSettings(prefs)
        assertFalse(settings.enabled)
        assertEquals(1f, settings.volumeFor(A), 0f)
        settings.choose(true)
        assertEquals(1f, settings.volumeFor(A), 0f)
        settings.remember(A, -6f)
        assertEquals(volumeForGain(-6f), settings.volumeFor(A), 0.00001f)
        assertEquals(volumeForGain(-6f), settings.volumeFor(null), 0.00001f)
    }

    @Test fun recreationKeepsTheSwitchAndEndpointSpecificGainWithoutTouchingOtherPreferences() {
        prefs.edit().putString("unrelated", "kept").commit()
        val original = ReplayGainSettings(prefs)
        original.choose(true)
        original.remember(A, -6f)
        original.remember(B, -18f)
        val restored = ReplayGainSettings(prefs)
        assertTrue(restored.enabled)
        assertEquals(-6f, restored.gainFor(A)!!, 0f)
        assertEquals(-18f, restored.gainFor(B)!!, 0f)
        assertEquals(volumeForGain(-6f), restored.volumeFor(A), 0.00001f)
        assertEquals(volumeForGain(-18f), restored.volumeFor(B), 0.00001f)
        assertEquals("kept", prefs.getString("unrelated", null))
    }

    @Test fun rememberedGainChangesInvalidateTheFallbackMedianAndIgnoreUnrelatedValues() {
        prefs.edit().putFloat("unrelated-number", -50f).putFloat("gain:invalid", Float.NaN).commit()
        val settings = ReplayGainSettings(prefs)
        settings.choose(true)
        settings.remember(A, -6f)
        settings.remember(B, -14f)
        assertEquals(volumeForGain(-10f), settings.volumeFor("unknown"), 0.00001f)
        settings.remember(B, -22f)
        assertEquals(volumeForGain(-14f), settings.volumeFor("unknown"), 0.00001f)
        assertEquals(volumeForGain(-6f), settings.volumeFor(A), 0.00001f)
    }

    @Test fun aSeparateSettingsOwnerReloadsTheToggleAndRetainsRememberedGain() {
        val ui = ReplayGainSettings(prefs)
        val serviceSettings = ReplayGainSettings(prefs)
        serviceSettings.remember(A, -12f)
        ui.choose(true)
        serviceSettings.reload()
        assertTrue(serviceSettings.enabled)
        assertEquals(volumeForGain(-12f), serviceSettings.volumeFor(A), 0.00001f)
        ui.choose(false)
        serviceSettings.reload()
        assertFalse(serviceSettings.enabled)
        assertEquals(1f, serviceSettings.volumeFor(A), 0f)
        assertEquals(-12f, serviceSettings.gainFor(A)!!, 0f)
    }

    @Test fun aWrongTypedSwitchUsesTheOffDefaultAndCanBeReplacedByAUserToggle() {
        prefs.edit().putString(ReplayGainSettings.KEY_ENABLED, "true")
            .putFloat("gain:" + A, -12f).putString("unrelated", "kept").commit()
        val settings = ReplayGainSettings(prefs)
        assertFalse(settings.enabled)
        assertEquals(1f, settings.volumeFor(A), 0f)
        assertEquals("true", prefs.getString(ReplayGainSettings.KEY_ENABLED, null))
        assertEquals(-12f, settings.gainFor(A)!!, 0f)
        settings.choose(true)
        val restored = ReplayGainSettings(prefs)
        assertTrue(restored.enabled)
        assertEquals(volumeForGain(-12f), restored.volumeFor(A), 0.00001f)
        assertEquals("kept", prefs.getString("unrelated", null))
    }

    @Test fun reloadingAWrongTypedSwitchUsesTheDefaultWithoutDeletingOtherValues() {
        val settings = ReplayGainSettings(prefs)
        settings.choose(true)
        settings.remember(A, -6f)
        prefs.edit().putInt(ReplayGainSettings.KEY_ENABLED, 1).commit()
        settings.reload()
        assertFalse(settings.enabled)
        assertEquals(1f, settings.volumeFor(A), 0f)
        assertEquals(1, prefs.getInt(ReplayGainSettings.KEY_ENABLED, 0))
        assertEquals(-6f, settings.gainFor(A)!!, 0f)
        settings.choose(true)
        settings.reload()
        assertTrue(settings.enabled)
    }

    @Test fun wrongTypedGainUsesTheExistingMedianThenAParsedGainReplacesOnlyThatEntry() {
        prefs.edit().putBoolean(ReplayGainSettings.KEY_ENABLED, true)
            .putString("gain:" + A, "-6").putFloat("gain:" + B, -12f)
            .putString("unrelated", "kept").commit()
        val settings = ReplayGainSettings(prefs)
        assertNull(settings.gainFor(A))
        assertEquals(volumeForGain(-12f), settings.volumeFor(A), 0.00001f)
        assertEquals("-6", prefs.getString("gain:" + A, null))
        settings.remember(A, -6f)
        val restored = ReplayGainSettings(prefs)
        assertEquals(-6f, restored.gainFor(A)!!, 0f)
        assertEquals(-12f, restored.gainFor(B)!!, 0f)
        assertEquals(volumeForGain(-9f), restored.volumeFor("unknown"), 0.00001f)
        assertEquals("kept", prefs.getString("unrelated", null))
    }

    private companion object {
        const val A = "http://192.168.1.20:7814/7"
        const val B = "http://192.168.1.21:7814/7"
    }
}
