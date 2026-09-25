package com.novelforge.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 把 WCAG 2.x 的相对亮度/对比度公式在 JVM 上重算一遍，锁住色板。
 *
 * 为什么不靠肉眼看：这套色板是「暖白纸面 + 墨色文字 + 朱砂主色」，
 * 主观上「浅底上的浅灰字看着挺清楚」，实测却常常在 AA 线以下。
 * 之前 onSurfaceVariant(#8A8272) 在 surface 上只有 3.80:1，而 outline 复用同一个颜色，
 * 连每张 PaperSurface 的边框也跟着塌到 3.80:1。
 * 公式自己实现一遍（不调用 Color.luminance）：断言不能依赖被测对象自己的换算。
 */
class ColorContrastTest {

    @Test
    fun lightSchemeTextPairsPassAA() = assertTextPairs(LIGHT_PAIRS, LIGHT)

    @Test
    fun darkSchemeTextPairsPassAA() = assertTextPairs(DARK_PAIRS, DARK)

    @Test
    fun lightSchemeOutlinesPassNonTextMinimum() = assertOutlinePairs(OUTLINE_PAIRS, LIGHT)

    @Test
    fun darkSchemeOutlinesPassNonTextMinimum() = assertOutlinePairs(OUTLINE_PAIRS, DARK)

    /** onError 压在 error 实底上；error 本身也是要读的失败提示，两处都得过线 */
    @Test
    fun errorTextIsReadableOnPageBackground() {
        assertAtLeast(contrast(LIGHT.getValue("error"), LIGHT.getValue("background")), TEXT_MINIMUM, "light error/background")
        assertAtLeast(contrast(DARK.getValue("error"), DARK.getValue("background")), TEXT_MINIMUM, "dark error/background")
    }

    /** 次要说明文字全站都在用 onSurfaceVariant：每一种浅色容器上都必须读得清 */
    @Test
    fun mutedBodyTextPassesOnEveryLightContainer() {
        val muted = LIGHT.getValue("onSurfaceVariant")
        listOf("background", "surface", "surfaceVariant", "primaryContainer").forEach { container ->
            assertAtLeast(
                contrast(muted, LIGHT.getValue(container)),
                TEXT_MINIMUM,
                "light onSurfaceVariant/$container"
            )
        }
    }

    /** 壁纸遮罩下限：再低，照片就直接顶在正文底下了 */
    @Test
    fun wallpaperDimRangeKeepsWallpaperFaint() {
        val light = wallpaperDimRange(dark = false)
        val dark = wallpaperDimRange(dark = true)
        assertAtLeast(light.start, 0.75f, "light 遮罩下限")
        assertAtLeast(dark.start, 0.60f, "dark 遮罩下限")
        assertTrue("遮罩上限不能超过 1", light.endInclusive <= 1f && dark.endInclusive <= 1f)
    }

    // ── WCAG 2.1 相对亮度与对比度 ──

    private fun channel(value: Float): Float =
        if (value <= 0.03928f) value / 12.92f else ((value + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun relativeLuminance(color: Color): Float =
        0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)

    private fun contrast(foreground: Color, background: Color): Float {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        return (max(a, b) + 0.05f) / (min(a, b) + 0.05f)
    }

    private fun assertTextPairs(pairs: List<String>, scheme: Map<String, Color>) =
        pairs.forEach { assertPair(it, scheme, TEXT_MINIMUM) }

    private fun assertOutlinePairs(pairs: List<String>, scheme: Map<String, Color>) =
        pairs.forEach { assertPair(it, scheme, NON_TEXT_MINIMUM) }

    private fun assertPair(pair: String, scheme: Map<String, Color>, minimum: Float) {
        val (foreground, background) = pair.split('/')
        assertAtLeast(
            contrast(scheme.getValue(foreground), scheme.getValue(background)),
            minimum,
            pair
        )
    }

    private fun assertAtLeast(actual: Float, minimum: Float, label: String) {
        assertTrue(
            "$label 对比度只有 %.2f:1，低于要求的 %.1f:1".format(actual, minimum),
            actual >= minimum
        )
    }

    // ── 色板快照 ──
    // 和 Theme.kt 里的 LightColors / DarkColors 一一对应，故意抄一份而不是反射取：
    // 少抄一个键 assertPair 会直接报「色板里没有 xxx」，改色板时也会被提醒同步。

    private val LIGHT: Map<String, Color> = mapOf(
        "primary" to Accent,
        "onPrimary" to PaperCard,
        "primaryContainer" to AccentSoft,
        "onPrimaryContainer" to Ink,
        "secondary" to SuccessGreen,
        "onSecondary" to PaperCard,
        "background" to Paper,
        "onBackground" to Ink,
        "surface" to PaperCard,
        "onSurface" to Ink,
        "surfaceVariant" to Color(0xFFF1EBDD),
        "onSurfaceVariant" to PaperMuted,
        "error" to AccentDeep,
        "onError" to PaperCard,
        "outline" to PaperMuted,
        "outlineVariant" to PaperLine
    )

    private val DARK: Map<String, Color> = mapOf(
        "primary" to Color(0xFFD8D3C8),
        "onPrimary" to Color(0xFF26221E),
        "primaryContainer" to Color(0xFF4A453E),
        "onPrimaryContainer" to Color(0xFFEAE4D8),
        "secondary" to Color(0xFFE8A33D),
        "onSecondary" to Color(0xFF26221E),
        "background" to Color(0xFF171614),
        "onBackground" to Color(0xFFDAD5CB),
        "surface" to Color(0xFF221F1C),
        "onSurface" to Color(0xFFDAD5CB),
        "surfaceVariant" to Color(0xFF2B2824),
        "onSurfaceVariant" to Color(0xFF9A9386),
        "error" to Color(0xFFD87464),
        "onError" to Color(0xFF26221E),
        "outline" to Color(0xFF847E72),
        "outlineVariant" to Color(0xFF7F796D)
    )

    /** on* 只能压在它自己对应的容器上，跨容器比没有意义（比如 onError 对 surface） */
    private val LIGHT_PAIRS = listOf(
        "onPrimary/primary",
        "onPrimaryContainer/primaryContainer",
        "onSecondary/secondary",
        "onBackground/background",
        "onSurface/surface",
        "onSurfaceVariant/surfaceVariant",
        "onError/error"
    )

    private val DARK_PAIRS = LIGHT_PAIRS

    /**
     * 描边族按 WCAG 1.4.11 的非文字 3:1。
     * 浅色 background 与 surface 只差 1.05:1，卡片边界全靠这条线，所以三种底色都要过。
     */
    private val OUTLINE_PAIRS = listOf(
        "outline/surface",
        "outline/background",
        "outline/surfaceVariant",
        "outlineVariant/surface",
        "outlineVariant/background",
        "outlineVariant/surfaceVariant"
    )

    private companion object {
        const val TEXT_MINIMUM = 4.5f
        const val NON_TEXT_MINIMUM = 3.0f
    }
}
