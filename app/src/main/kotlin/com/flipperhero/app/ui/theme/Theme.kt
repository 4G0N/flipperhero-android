package com.flipperhero.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/*
 * Flipper Zero palette: orange on black. Contrast is checked against WCAG 2.x AA (>= 4.5:1 for body
 * text, >= 3:1 for large text and UI borders), which covers the ADA legibility bar.
 *
 * Brand orange #FF8200 has a relative luminance of ~0.37. Black text on it is ~8.4:1 (AAA) while
 * white text on it is only ~2.5:1 (fails), so filled orange always carries BLACK labels (onPrimary),
 * and in light mode the accent darkens to #9A4A00 (~6.2:1 on white, both as fill-with-white-text and
 * as tinted text) so orange-on-white never drops below AA.
 */

private val Orange = Color(0xFFFF8200)        // brand; fills carry black text
private val OrangeBright = Color(0xFFFFA64D)
private val OrangeDeep = Color(0xFF9A4A00)     // light-mode accent that clears AA on white

private val DarkColors = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF000000),             // 8.4:1 on orange
    primaryContainer = Color(0xFF3D2200),
    onPrimaryContainer = Color(0xFFFFB870),    // ~7.6:1 on its container
    secondary = OrangeBright,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF402A10),
    onSecondaryContainer = Color(0xFFFFD6A8),
    tertiary = OrangeBright,
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF5F3EF),          // ~19:1 on black
    surface = Color(0xFF14110C),               // warm near-black for cards/bars
    onSurface = Color(0xFFF5F3EF),
    surfaceVariant = Color(0xFF2A241C),
    onSurfaceVariant = Color(0xFFD2C9BC),       // ~9:1 on surfaceVariant
    outline = Color(0xFF9A8C78),                // >= 3:1 on black, for borders
    outlineVariant = Color(0xFF4A4036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFF5F3EF),
    inverseOnSurface = Color(0xFF14110C),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = OrangeDeep,
    onPrimary = Color(0xFFFFFFFF),             // ~6.2:1 on #9A4A00
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF301400),
    secondary = Color(0xFF8A5100),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF2C1600),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBF5),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF2E0CF),
    onSurfaceVariant = Color(0xFF51443A),       // ~7:1 on surfaceVariant
    outline = Color(0xFF837468),
    outlineVariant = Color(0xFFD7C3B2),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

/** Monospace everywhere, for the command-line / Flipper-LCD feel. Sizes are the M3 defaults, so
 *  dynamic type still scales the whole UI; only the family changes. */
private val MonoType: Typography = Typography().let { d ->
    val m = FontFamily.Monospace
    d.copy(
        displayLarge = d.displayLarge.copy(fontFamily = m),
        displayMedium = d.displayMedium.copy(fontFamily = m),
        displaySmall = d.displaySmall.copy(fontFamily = m),
        headlineLarge = d.headlineLarge.copy(fontFamily = m),
        headlineMedium = d.headlineMedium.copy(fontFamily = m),
        headlineSmall = d.headlineSmall.copy(fontFamily = m),
        titleLarge = d.titleLarge.copy(fontFamily = m),
        titleMedium = d.titleMedium.copy(fontFamily = m),
        titleSmall = d.titleSmall.copy(fontFamily = m),
        bodyLarge = d.bodyLarge.copy(fontFamily = m),
        bodyMedium = d.bodyMedium.copy(fontFamily = m),
        bodySmall = d.bodySmall.copy(fontFamily = m),
        labelLarge = d.labelLarge.copy(fontFamily = m),
        labelMedium = d.labelMedium.copy(fontFamily = m),
        labelSmall = d.labelSmall.copy(fontFamily = m),
    )
}

@Composable
fun FlipperHeroTheme(content: @Composable () -> Unit) {
    // No forced font scale: text uses sp and honours the system Display size / Font size settings
    // (dynamic type). Colors follow the system light/dark setting.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MonoType,
        content = content,
    )
}
