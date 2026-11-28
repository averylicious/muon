package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceSettingsTest {
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("appearance-settings-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // Expressive is process-wide, and other tests run in this same process.
    @After fun restoreSwitches() {
        Expressive.motion = true
        Expressive.blur = true
    }

    @Test fun aFreshInstallGetsFrostedBars() {
        assertEquals(BarSurface.Frosted, AppearanceSettings(prefs).barSurface)
    }

    /** An upgrade from Blur off keeps its solid bars, and turning Blur on brings Frosted back. */
    @Test fun blurIsTheMasterSwitchForFrostedBars() {
        prefs.edit().putBoolean("expressive_blur", false).commit()
        val settings = AppearanceSettings(prefs)
        assertEquals(BarSurface.Solid, effectiveBarSurface(settings.barSurface, Expressive.blur, 34))
        settings.chooseExpressiveBlur(true)
        assertEquals(BarSurface.Frosted, effectiveBarSurface(settings.barSurface, Expressive.blur, 34))
    }

    @Test fun aChosenSurfaceSurvivesARestart() {
        AppearanceSettings(prefs).chooseBarSurface(BarSurface.Tinted)
        assertEquals(BarSurface.Tinted, AppearanceSettings(prefs).barSurface)
    }
}
