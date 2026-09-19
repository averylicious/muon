package dev.avery.muon

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * Google Sans Flex, the face the 2.0 mockups are drawn in, at roundness 100 to match the phone's
 * own interface.
 *
 * One variable font ships rather than a set of static weights. Roundness, grade, slant and width
 * are instanced away; weight and optical size stay variable, which is both smaller than shipping
 * four static weights and the only way to honour #40's requirement that the optical-size axis
 * follows the rendered size. `docs/fonts.md` records the source, the command and the measurements.
 */
private val Weights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

/**
 * The family for one rendered size. Optical size is a property of the typeface, not of the text
 * style, so each role gets a family asking the font for its own size: at 36sp the face opens up,
 * and at 11sp it tightens. Every family reads the same font file.
 */
private fun googleSansFlex(opticalSize: TextUnit): FontFamily = FontFamily(
    Weights.map { weight ->
        Font(R.font.google_sans_flex, weight, FontStyle.Normal,
            variationSettings = FontVariation.Settings(weight, FontStyle.Normal,
                FontVariation.opticalSizing(opticalSize)))
    }
)

/** A role keeps its Material 3 size and spacing; only the face changes. */
private fun TextStyle.onGoogleSansFlex(): TextStyle =
    copy(fontFamily = googleSansFlex(if (fontSize.isSpecified) fontSize else 14.sp))

/**
 * Material 3's own scale, on Muon's face. The sizes are Material's, measured against Android's
 * Settings app: an expanded screen title is `displaySmall` at 36sp and regular weight, and a
 * collapsed one is `titleLarge` at 22sp. Bold is a call-site exception, not a role.
 */
internal val MuonTypography = Typography().run {
    copy(
        displayLarge = displayLarge.onGoogleSansFlex(),
        displayMedium = displayMedium.onGoogleSansFlex(),
        displaySmall = displaySmall.onGoogleSansFlex(),
        headlineLarge = headlineLarge.onGoogleSansFlex(),
        headlineMedium = headlineMedium.onGoogleSansFlex(),
        headlineSmall = headlineSmall.onGoogleSansFlex(),
        titleLarge = titleLarge.onGoogleSansFlex(),
        titleMedium = titleMedium.onGoogleSansFlex(),
        titleSmall = titleSmall.onGoogleSansFlex(),
        bodyLarge = bodyLarge.onGoogleSansFlex(),
        bodyMedium = bodyMedium.onGoogleSansFlex(),
        bodySmall = bodySmall.onGoogleSansFlex(),
        labelLarge = labelLarge.onGoogleSansFlex(),
        labelMedium = labelMedium.onGoogleSansFlex(),
        labelSmall = labelSmall.onGoogleSansFlex(),
    )
}
