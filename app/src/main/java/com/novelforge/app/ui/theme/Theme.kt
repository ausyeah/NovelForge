package com.novelforge.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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
// 对比度全部按 WCAG 实算过：正文 on* 相对各自容器 ≥4.5:1，描边族相对纸面 ≥3:1
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
    // 原来 #948D80 在 surfaceVariant 上只有 4.46:1，差 0.04 没过 AA
    onSurfaceVariant = Color(0xFF9A9386),
    // 原来 #D06A5A 配 onError 只有 4.43:1，失败提示（最该看清的一行字）反而最看不清
    error = Color(0xFFD87464),
    onError = Color(0xFF26221E),
    // 原来 2.93:1 / 1.37:1，都没过非文字 3:1
    outline = Color(0xFF847E72),
    outlineVariant = Color(0xFF7F796D)
)

/**
 * 壁纸遮罩的可调区间。浅色纸面本来就亮，照片透出来 25% 就够读；
 * 深色底压 40% 照片还能看出氛围。旧区间下限 0.35 等于让 65% 的照片直接顶在正文底下。
 */
val LIGHT_WALLPAPER_DIM_RANGE = 0.75f..0.95f
val DARK_WALLPAPER_DIM_RANGE = 0.6f..0.95f

/** 设置页滑块和主题共用同一份区间，避免两边各写一个数字又对不上。 */
fun wallpaperDimRange(dark: Boolean): ClosedFloatingPointRange<Float> =
    if (dark) DARK_WALLPAPER_DIM_RANGE else LIGHT_WALLPAPER_DIM_RANGE

/** 顶栏/状态栏压在照片上最容易糊，顶部额外叠一段渐变纸色给文字一个稳定的对比度地板。 */
private val TopScrimHeight = 132.dp

@Composable
fun NovelForgeTheme(
    darkTheme: Boolean = false,
    wallpaper: ImageBitmap? = null,
    wallpaperDim: Float = 0.62f,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val range = wallpaperDimRange(darkTheme)
    val scrim = wallpaperDim.coerceIn(range)

    CompositionLocalProvider(LocalNovelForgeDark provides darkTheme) {
        MaterialTheme(colorScheme = colors, typography = PaperTypography) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // 叠在照片和纸色 Surface 之间：两层半透明纸色会叠乘，
                    // 顶部实际不透明度接近 0.99，标题栏再也不会被高光照片吃掉
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TopScrimHeight)
                            .background(
                                Brush.verticalGradient(
                                    0f to colors.background.copy(alpha = scrim),
                                    1f to colors.background.copy(alpha = 0f)
                                )
                            )
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (wallpaper != null) colors.background.copy(alpha = scrim) else colors.background,
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
}
