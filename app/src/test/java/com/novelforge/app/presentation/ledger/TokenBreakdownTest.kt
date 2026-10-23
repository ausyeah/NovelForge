package com.novelforge.app.presentation.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账本里那行 token 明细。
 *
 * 最重要的一条是 [cacheHitMustNotSwallowTheOutputCount]：
 * 原实现用 `+` 拼字符串再接 `if`，而 Kotlin 里 `+` 优先级高于 `if`，
 * else 分支会一路吃到表达式末尾，于是
 *
 * ```
 * "入 " + f(in) + if (cached > 0) "（缓存…）" else "" + " · 出 " + f(out)
 * ```
 *
 * 实际解析成
 *
 * ```
 * "入 " + f(in) + (if (cached > 0) "（缓存…）" else ("" + " · 出 " + f(out)))
 * ```
 *
 * **命中缓存时，输出那一整段消失。** 而缓存命中的场景恰恰是用户最想看
 * 数字的时候。现在 tokenBreakdown 先算好每段再拼，优先级无从作怪。
 */
class TokenBreakdownTest {

    @Test
    fun cacheHitMustNotSwallowTheOutputCount() {
        val line = tokenBreakdown(input = 9_000, cached = 8_000, output = 900)
        assertTrue("命中缓存时输出不见了：$line", line.contains("出"))
        assertTrue("命中缓存时输出数值不见了：$line", line.contains("900"))
        assertTrue("缓存数值不见了：$line", line.contains("8000"))
        assertTrue("输入数值不见了：$line", line.contains("9000"))
    }

    @Test
    fun withoutCacheTheLineIsJustInputAndOutput() {
        assertEquals("入 9000 · 出 900", tokenBreakdown(9_000, 0, 900))
    }

    @Test
    fun cacheIsAnnotatedWhenPresent() {
        val line = tokenBreakdown(input = 9_000, cached = 8_000, output = 900)
        assertEquals("入 9000（缓存 8000） · 出 900", line)
    }

    @Test
    fun zeroCacheIsHiddenInLogs_butShownInRankings() {
        // 流水账里 0 缓存不值一提；排行是拿来横向比模型的，
        // "这个模型一点缓存都没命中"本身就是信息
        assertFalse(tokenBreakdown(100, 0, 50).contains("缓存"))
        assertTrue(tokenBreakdown(100, 0, 50, showCacheWhenZero = true).contains("缓存 0"))
    }

    @Test
    fun largeNumbersAreAbbreviated() {
        // formatTokens 在 1 万 / 1 亿处缩写，所以上面的用例都刻意用 4 位数，
        // 好让断言直接对得上原始数字
        assertEquals("入 1.2万（缓存 8000） · 出 900", tokenBreakdown(12_345, 8_000, 900))
        assertEquals("入 2.0亿 · 出 0", tokenBreakdown(200_000_000, 0, 0))
    }

    @Test
    fun inputOutputCacheAllZeroStillRendersBothSides() {
        // 0 也得有「入」「出」两个字，否则空行看着像渲染坏了
        val line = tokenBreakdown(0, 0, 0)
        assertTrue(line.contains("入"))
        assertTrue(line.contains("出"))
    }

    @Test
    fun reasoningNeverLeaksIntoTheLine() {
        // 思考 token 含在 output 里，单列一遍只会让人以为总额算错了。
        // 这个函数压根不接收 reasoning 参数 —— 编译期就堵死。
        val line = tokenBreakdown(input = 100, cached = 0, output = 40)
        assertFalse(line.contains("思考"))
        assertFalse(line.contains("reasoning"))
    }
}
