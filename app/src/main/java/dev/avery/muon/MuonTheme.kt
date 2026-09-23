package dev.avery.muon

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val MuonDarkColors = darkColorScheme(
    primary = Color(0xFFBCF580),
    onPrimary = Color(0xFF152700),
    background = Color(0xFF101411),
    surface = Color(0xFF101411),
    surfaceVariant = Color(0xFF252D27),
    secondaryContainer = Color(0xFF283E2A),
    onSecondaryContainer = Color(0xFFD3F8B5),
    onSurface = Color(0xFFF3F4EF),
    onSurfaceVariant = Color(0xFFA9B4A9),
)

private val MuonLightColors = lightColorScheme(
    primary = Color(0xFF3D6800),
    onPrimary = Color.White,
    background = Color(0xFFF9FAF4),
    surface = Color(0xFFF9FAF4),
    surfaceVariant = Color(0xFFE1E8DA),
    secondaryContainer = Color(0xFFD9E7CB),
    onSecondaryContainer = Color(0xFF172E0B),
    onSurface = Color(0xFF1A1C18),
    onSurfaceVariant = Color(0xFF454A40),
)

@Composable
fun MuonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    blackSurfaces: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> MuonDarkColors
        else -> MuonLightColors
    }
    // Only the ground goes black. Containers keep their own colour so cards, the mini player and
    // artwork placeholders stay visible against it, and accents are left alone entirely.
    val scheme = if (blackSurfaces && darkTheme) {
        colors.copy(background = Color.Black, surface = Color.Black)
    } else colors
    MaterialTheme(colorScheme = scheme, typography = MuonTypography, content = content)
}
