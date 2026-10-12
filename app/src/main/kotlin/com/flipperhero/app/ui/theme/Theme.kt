package com.flipperhero.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val Dark = darkColorScheme()
private val Light = lightColorScheme()

@Composable
fun FlipperHeroTheme(content: @Composable () -> Unit) {
    // No forced font scale: text uses sp and honours the system Display size / Font size settings
    // (dynamic type). Colors follow the system light/dark setting.
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
