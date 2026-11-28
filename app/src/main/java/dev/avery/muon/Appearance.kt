package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** Where Muon's colours come from. Material You is the default; the Muon palette is the opt-out. */
enum class PaletteChoice { MaterialYou, Muon }

internal fun paletteChoiceFrom(stored: String?): PaletteChoice =
    if (stored == PaletteChoice.Muon.name) PaletteChoice.Muon else PaletteChoice.MaterialYou

/**
 * Pure black backgrounds only mean anything while the system is in dark mode, and they are a
 * modifier on the chosen palette rather than a third palette: accents stay where they came from.
 */
internal fun useBlackSurfaces(amoled: Boolean, darkTheme: Boolean): Boolean = amoled && darkTheme

/** Dynamic colour arrived in Android 12; Muon still supports Android 9. */
internal fun dynamicColorAvailable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

/**
 * The palette that actually renders. A device without dynamic colour cannot honour Material You,
 * so the setting reports the Muon palette rather than offering a choice that does nothing.
 */
internal fun effectivePalette(choice: PaletteChoice, dynamicAvailable: Boolean): PaletteChoice =
    if (choice == PaletteChoice.MaterialYou && !dynamicAvailable) PaletteChoice.Muon else choice

internal fun paletteLabel(choice: PaletteChoice): String = when (choice) {
    PaletteChoice.MaterialYou -> "Material You"
    PaletteChoice.Muon -> "Muon"
}

internal fun paletteDescription(choice: PaletteChoice): String = when (choice) {
    PaletteChoice.MaterialYou -> "Accents follow your wallpaper."
    PaletteChoice.Muon -> "The app's own green palette."
}

/** What the navigation bar and mini player are made of. The content scrolls on under Frosted and Tinted. */
enum class BarSurface { Frosted, Tinted, Solid }

/** Before Bar surface existed, the Blur switch decided whether the bars were frosted or solid. */
internal fun barSurfaceFrom(stored: String?, legacyBlur: Boolean): BarSurface =
    BarSurface.entries.firstOrNull { it.name == stored } ?: if (legacyBlur) BarSurface.Frosted else BarSurface.Solid

/** Frost needs `RenderEffect`, from Android 12; older phones keep the solid bars they always had. */
internal fun effectiveBarSurface(choice: BarSurface, sdkInt: Int): BarSurface =
    if (choice == BarSurface.Frosted && sdkInt < Build.VERSION_CODES.S) BarSurface.Solid else choice

internal fun barSurfaceLabel(choice: BarSurface): String = when (choice) {
    BarSurface.Frosted -> "Frosted"
    BarSurface.Tinted -> "Tinted"
    BarSurface.Solid -> "Solid"
}

/**
 * The Material 3 Expressive experiment's two switches (Canary only; see docs/expressive-experiment.md).
 * Held here rather than in [AppearanceSettings] because the motion specs in Motion.kt are built
 * outside composition and read these directly; being snapshot state, a flip still re-animates.
 */
internal object Expressive {
    /** Expressive springs for Material's components and Muon's own transitions, instead of eased tweens. */
    var motion by mutableStateOf(true)
    /** Blurs the library behind the player and the song actions sheet, as Android 17 does behind its shade. */
    var blur by mutableStateOf(true)
}

/**
 * Stored apart from the connection preferences on purpose: disconnecting clears those, and an
 * appearance choice should survive changing servers.
 */
@Stable
class AppearanceSettings(private val prefs: SharedPreferences) {
    var palette by mutableStateOf(paletteChoiceFrom(prefs.getString(KEY_PALETTE, null)))
        private set
    var amoled by mutableStateOf(prefs.getBoolean(KEY_AMOLED, false))
        private set
    var barSurface by mutableStateOf(barSurfaceFrom(prefs.getString(KEY_BAR_SURFACE, null), prefs.getBoolean(KEY_EXPRESSIVE_BLUR, true)))
        private set

    init {
        Expressive.motion = prefs.getBoolean(KEY_EXPRESSIVE_MOTION, true)
        Expressive.blur = prefs.getBoolean(KEY_EXPRESSIVE_BLUR, true)
        // Written once, so a later Blur switch flip cannot change the bars of someone who never chose.
        if (!prefs.contains(KEY_BAR_SURFACE)) prefs.edit().putString(KEY_BAR_SURFACE, barSurface.name).apply()
    }

    fun chooseBarSurface(choice: BarSurface) {
        if (choice == barSurface) return
        barSurface = choice
        prefs.edit().putString(KEY_BAR_SURFACE, choice.name).apply()
    }

    fun choose(choice: PaletteChoice) {
        if (choice == palette) return
        palette = choice
        prefs.edit().putString(KEY_PALETTE, choice.name).apply()
    }

    fun chooseAmoled(enabled: Boolean) {
        if (enabled == amoled) return
        amoled = enabled
        prefs.edit().putBoolean(KEY_AMOLED, enabled).apply()
    }

    fun chooseExpressiveMotion(enabled: Boolean) {
        Expressive.motion = enabled
        prefs.edit().putBoolean(KEY_EXPRESSIVE_MOTION, enabled).apply()
    }

    fun chooseExpressiveBlur(enabled: Boolean) {
        Expressive.blur = enabled
        prefs.edit().putBoolean(KEY_EXPRESSIVE_BLUR, enabled).apply()
    }

    private companion object {
        const val KEY_PALETTE = "palette"
        const val KEY_AMOLED = "amoled"
        const val KEY_EXPRESSIVE_MOTION = "expressive_motion"
        const val KEY_EXPRESSIVE_BLUR = "expressive_blur"
        const val KEY_BAR_SURFACE = "bar_surface"
    }
}

@Composable
fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current
    return remember(context) {
        AppearanceSettings(context.getSharedPreferences("appearance", Context.MODE_PRIVATE))
    }
}
