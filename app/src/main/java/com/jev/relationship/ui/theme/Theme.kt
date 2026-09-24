package com.jev.relationship.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    secondary = Color(0xFF34C759),
    background = Color(0xFFF2F2F7),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC6C1FF),
    secondary = Color(0xFF53DBC6),
)

@Composable
fun JevTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
