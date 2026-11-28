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

/** Edge to edge is Material's bar along the bottom; Floating puts the mini player and the tabs in capsules over the list. */
enum class NavBarStyle { EdgeToEdge, Floating }

internal fun navBarStyleFrom(stored: String?): NavBarStyle =
    NavBarStyle.entries.firstOrNull { it.name == stored } ?: NavBarStyle.EdgeToEdge

/** The floating bar's share of the width inside its margins, from [FLOAT_WIDTH_MIN] to all of it, in [FLOAT_WIDTH_STEP]s. */
internal const val FLOAT_WIDTH_MIN = 0.6f
internal const val FLOAT_WIDTH_STEP = 0.05f

/** The floating tabs' height in dp, from [FLOAT_TAB_HEIGHT_MIN] to [FLOAT_TAB_HEIGHT_MAX] in [FLOAT_TAB_HEIGHT_STEP]s. */
internal const val FLOAT_TAB_HEIGHT_MIN = 44
internal const val FLOAT_TAB_HEIGHT_MAX = 60
internal const val FLOAT_TAB_HEIGHT_STEP = 4
internal const val FLOAT_TAB_HEIGHT_DEFAULT = 52

/** A stored width outside the range, or unreadable, falls back into it rather than drawing a sliver. */
internal fun floatWidthFrom(stored: Float): Float = if (stored.isNaN()) 1f else stored.coerceIn(FLOAT_WIDTH_MIN, 1f)

internal fun floatTabHeightFrom(stored: Int): Int = stored.coerceIn(FLOAT_TAB_HEIGHT_MIN, FLOAT_TAB_HEIGHT_MAX)

internal fun navBarStyleLabel(style: NavBarStyle): String = when (style) {
    NavBarStyle.EdgeToEdge -> "Edge to edge"
    NavBarStyle.Floating -> "Floating"
}

/** What the navigation bar and mini player are made of. The content scrolls on under Frosted and Tinted. */
enum class BarSurface { Frosted, Tinted, Solid }

internal fun barSurfaceFrom(stored: String?): BarSurface =
    BarSurface.entries.firstOrNull { it.name == stored } ?: BarSurface.Frosted

/**
 * Frosted needs the Blur switch, which is the kill switch for phones that struggle with blur, and
 * `RenderEffect` (Android 12). Without either the bars are Solid, the look they had with Blur off
 * before Bar surface existed; the stored choice is kept for when Frosted is possible again.
 */
internal fun frostedAvailable(blur: Boolean, sdkInt: Int): Boolean = blur && sdkInt >= Build.VERSION_CODES.S

internal fun effectiveBarSurface(choice: BarSurface, blur: Boolean, sdkInt: Int): BarSurface =
    if (choice == BarSurface.Frosted && !frostedAvailable(blur, sdkInt)) BarSurface.Solid else choice

internal fun barSurfaceLabel(choice: BarSurface): String = when (choice) {
    BarSurface.Frosted -> "Frosted"
    BarSurface.Tinted -> "Tinted"
    BarSurface.Solid -> "Solid"
}

/**
 * Held here rather than in [AppearanceSettings] because the draw-phase blur code reads it outside
 * composition; being snapshot state, a flip still redraws.
 */
internal object Expressive {
    /** Every blur Muon draws: Frosted bars, Now Playing's cover, and the library behind the player and song menu. */
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
    var barSurface by mutableStateOf(barSurfaceFrom(prefs.getString(KEY_BAR_SURFACE, null)))
        private set
    var navBar by mutableStateOf(navBarStyleFrom(prefs.getString(KEY_NAV_BAR, null)))
        private set
    /** Floating only: scrolling down folds the tabs to the current one, with the mini player beside it. */
    var tuckIn by mutableStateOf(prefs.getBoolean(KEY_TUCK_IN, false))
        private set
    var floatWidth by mutableStateOf(floatWidthFrom(prefs.getFloat(KEY_FLOAT_WIDTH, 1f)))
        private set
    var floatTabHeight by mutableStateOf(floatTabHeightFrom(prefs.getInt(KEY_FLOAT_TAB_HEIGHT, FLOAT_TAB_HEIGHT_DEFAULT)))
        private set

    init {
        Expressive.blur = prefs.getBoolean(KEY_EXPRESSIVE_BLUR, true)
    }

    fun chooseNavBar(style: NavBarStyle) {
        if (style == navBar) return
        navBar = style
        prefs.edit().putString(KEY_NAV_BAR, style.name).apply()
    }

    fun chooseTuckIn(enabled: Boolean) {
        if (enabled == tuckIn) return
        tuckIn = enabled
        prefs.edit().putBoolean(KEY_TUCK_IN, enabled).apply()
    }

    /** Shown at once, for a slider being dragged; [saveFloatSize] stores it when the finger lifts. */
    fun previewFloatWidth(share: Float) { floatWidth = floatWidthFrom(share) }

    fun previewFloatTabHeight(dp: Int) { floatTabHeight = floatTabHeightFrom(dp) }

    fun saveFloatSize() {
        prefs.edit().putFloat(KEY_FLOAT_WIDTH, floatWidth).putInt(KEY_FLOAT_TAB_HEIGHT, floatTabHeight).apply()
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

    fun chooseExpressiveBlur(enabled: Boolean) {
        Expressive.blur = enabled
        prefs.edit().putBoolean(KEY_EXPRESSIVE_BLUR, enabled).apply()
    }

    private companion object {
        const val KEY_PALETTE = "palette"
        const val KEY_AMOLED = "amoled"
        const val KEY_EXPRESSIVE_BLUR = "expressive_blur"
        const val KEY_BAR_SURFACE = "bar_surface"
        const val KEY_NAV_BAR = "nav_bar"
        const val KEY_TUCK_IN = "tuck_in"
        const val KEY_FLOAT_WIDTH = "float_width"
        const val KEY_FLOAT_TAB_HEIGHT = "float_tab_height"
    }
}

@Composable
fun rememberAppearanceSettings(): AppearanceSettings {
    val context = LocalContext.current
    return remember(context) {
        AppearanceSettings(context.getSharedPreferences("appearance", Context.MODE_PRIVATE))
    }
}
