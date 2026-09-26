package com.novelforge.app.presentation.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式发布的节流曲线。
 *
 * ## 这条曲线改过两次，两次的理由都不是「感觉」
 *
 * **第一版**：照着「排版才是大头」定的 —— 每发布一次 Compose 要给最后一条重排
 * 整段 MultiParagraph，而这部分随正文变长而变贵，于是上限压到 250ms。
 *
 * **那个前提是错的。** 排版是按**段落**的：`remember(source, …)` 逐块缓存，
 * 流式时只有尾部那一块拿到新的 AnnotatedString，只有它重建 MultiParagraph。
 * 逐块缓存是后加的，写第一版注释时还没有。实测最坏单块解析恒定 0.017–0.035ms，
 * **不随整篇长度增长**。单次发布的开销由尾部那一块决定，跟正文多长基本无关。
 *
 * **第二版（现在）**：既然单次开销不随长度涨，长度就不该主导刷新率。
 * 上限 250ms → 150ms，斜率 chars/25 → chars/60。
 *
 * 用户原话：「跟随滑动因为渲染的问题还是感觉卡卡的」。
 * `reverseLayout = true` 下每发布一次整个视口平移一次，4Hz 就是每秒 4 次
 * 30–60px 的整体位移 —— 眼睛读这个就是卡，**跟单次耗时够不够没关系**。
 *
 * ## `longOutputNeverGetsSlowEnoughToLookSteppy` 原来自己就矛盾
 *
 * 旧版叫 `neverSlowerThanFiveHertz`，断言却是 `<= 250L` —— 250ms 是 4Hz，
 * 名字里的 5Hz 从来没成立过。而「再慢就明显看得出『一跳一跳』」那句话
 * 就写在被它守着的函数注释里，8000 字以上又正好顶在 250ms。
 * 护栏当时设了一个自己声明「不能超过」的上限，然后守着它。
 *
 * 教训：**断言的名字要跟断言的值对得上**，否则它会安静地守着一个自己
 * 都不认可的上限，直到有人抱怨。
 */
class StreamPublishIntervalTest {

    @Test
    fun shortOutputStaysAtFullSpeed() {
        // 短回答必须跟手，不然"模型秒回"却看着像卡了一下
        assertEquals(50L, streamPublishIntervalMs(0))
        assertEquals(50L, streamPublishIntervalMs(200))
        assertEquals(50L, streamPublishIntervalMs(400))
    }

    @Test
    fun intervalGrowsWithLength() {
        val samples = listOf(0, 500, 1000, 2000, 4000, 8000)
            .map { streamPublishIntervalMs(it) }
        // 逐级变慢，且严格单调递增
        samples.zipWithNext { a, b -> assertTrue("$a -> $b 没有变慢", b > a) }
    }

    @Test
    fun longOutputNeverGetsSlowEnoughToLookSteppy() {
        // 150ms = 6.7Hz。这条原来是 `<= 250L`，名字叫 `neverSlowerThanFiveHertz` ——
        // **名字和断言互相矛盾**：250ms 是 4Hz，压根不是 5Hz。
        //
        // 而那句「250ms 再慢就明显看得出『一跳一跳』」就写在被它守着的函数注释里，
        // 8000 字以上的回答又正好顶在 250ms。也就是说这条护栏当时设了一个
        // 自己声明「不能超过」的上限，然后守着它。
        //
        // 真的把「一跳一跳」消掉的是发布频率，不是单次耗时：reverseLayout 下
        // 每发布一次整个视口平移「这一段时间新到的行高」，4Hz 就是每秒 4 次
        // 30–60px 的整体位移。单次渲染再快也救不了这个。
        assertTrue("100000 字时退到 4Hz 以下：${streamPublishIntervalMs(100_000)}ms",
            streamPublishIntervalMs(100_000) <= 150L)
        assertTrue("4000 字时退到 4Hz 以下：${streamPublishIntervalMs(4000)}ms",
            streamPublishIntervalMs(4000) <= 150L)
    }

    @Test
    fun fourThousandCharsStaysUnderTenHertz() {
        // 一篇长回答（4000 字）应该退到 10Hz 以下，而不是还在 20Hz 上硬撑。
        // 单次发布的开销由**尾部那一块**决定（逐块 remember 缓存），跟正文多长
        // 基本无关，所以这里不必退到 4Hz 那种看着一跳一跳的频率。
        assertTrue(
            "4000 字时仍在 10Hz 以上：${streamPublishIntervalMs(4000)}ms",
            streamPublishIntervalMs(4000) >= 100L
        )
    }

    @Test
    fun isMonotonicAcrossTheWholeRange() {
        var previous = streamPublishIntervalMs(0)
        for (chars in 1..12_000 step 137) {
            val now = streamPublishIntervalMs(chars)
            assertTrue("chars=$chars 间隔反而变短了：$previous -> $now", now >= previous)
            previous = now
        }
    }
}
