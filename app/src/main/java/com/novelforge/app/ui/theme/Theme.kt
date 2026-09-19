package com.novelforge.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** 应用内实际生效的深/浅色（跟随 themeMode 手动切换，而非系统 isSystemInDarkTheme） */
val LocalNovelForgeDark = staticCompositionLocalOf { false }

private val SerifFont = FontFamily.Serif

// 在默认排版基础上统一换成衬线（宋体）字形，贴合纸质观感
private val PaperTypography = run {
    val base = Typography()
    fun t(style: androidx.compose.ui.text.TextStyle) = style.copy(fontFamily = SerifFont)
    Typography(
        headlineLarge = t(base.headlineLarge),
        headlineMedium = t(base.headlineMedium),
        titleLarge = t(base.titleLarge),
        titleMedium = t(base.titleMedium),
        titleSmall = t(base.titleSmall),
        bodyLarge = t(base.bodyLarge),
        bodyMedium = t(base.bodyMedium),
        bodySmall = t(base.bodySmall),
        labelLarge = t(base.labelLarge),
        labelMedium = t(base.labelMedium),
        labelSmall = t(base.labelSmall)
    )
}

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = PaperCard,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Ink,
    secondary = SuccessGreen,
    onSecondary = PaperCard,
    background = Paper,
    onBackground = Ink,
    surface = PaperCard,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EBDD),
    onSurfaceVariant = PaperMuted,
    error = AccentDeep,
    onError = PaperCard,
    outline = PaperMuted,
    outlineVariant = PaperLine
)

// 深色＝墨纸：中性暖黑底、细描边卡片、亮灰主按钮、橙/朱砂点缀（参考深色截图）
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD8D3C8),
    onPrimary = Color(0xFF26221E),
    primaryContainer = Color(0xFF4A453E),
    onPrimaryContainer = Color(0xFFEAE4D8),
    secondary = Color(0xFFE8A33D),
    onSecondary = Color(0xFF26221E),
    background = Color(0xFF171614),
    onBackground = Color(0xFFDAD5CB),
    surface = Color(0xFF221F1C),
    onSurface = Color(0xFFDAD5CB),
    surfaceVariant = Color(0xFF2B2824),
    onSurfaceVariant = Color(0xFF948D80),
    error = Color(0xFFD06A5A),
    onError = Color(0xFF26221E),
    outline = Color(0xFF6E675C),
    outlineVariant = Color(0xFF3A3631)
)

@Composable
fun NovelForgeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors

    CompositionLocalProvider(LocalNovelForgeDark provides darkTheme) {
        MaterialTheme(colorScheme = colors, typography = PaperTypography) {
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
}
