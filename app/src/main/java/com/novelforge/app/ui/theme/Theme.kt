package com.novelforge.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    onSurfaceVariant = Color(0xFF374151),
    outline = Color(0xFF6B7280),
    outlineVariant = Color(0xFFD1D5DB)
)

private val DarkColors = darkColorScheme(
    primary = ColorTokens.DarkPrimary,
    onPrimary = ColorTokens.DarkOnPrimary,
    background = ColorTokens.DarkBackground,
    onBackground = ColorTokens.DarkOnBackground,
    surface = ColorTokens.DarkSurface,
    onSurface = ColorTokens.DarkOnSurface,
    onSurfaceVariant = ColorTokens.DarkOnSurfaceVariant,
    outline = ColorTokens.DarkOutline
)

private object ColorTokens {
    val DarkPrimary = androidx.compose.ui.graphics.Color(0xFFC4B5FD)
    val DarkOnPrimary = androidx.compose.ui.graphics.Color(0xFF2E1065)
    val DarkBackground = androidx.compose.ui.graphics.Color(0xFF141218)
    val DarkOnBackground = androidx.compose.ui.graphics.Color(0xFFE6E1E9)
    val DarkSurface = androidx.compose.ui.graphics.Color(0xFF141218)
    val DarkOnSurface = androidx.compose.ui.graphics.Color(0xFFE6E1E9)
    val DarkOnSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFCAC4D0)
    val DarkOutline = androidx.compose.ui.graphics.Color(0xFF938F99)
}

@Composable
fun NovelForgeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = colors.background,
            contentColor = colors.onBackground
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                content()
            }
        }
    }
}
