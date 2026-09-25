package com.novelforge.app.presentation.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式发布的节流曲线。
 *
 * 这条曲线是照着"排版才是大头"这件事定的：解析整篇 5000 字只要 0.1-0.3ms
 * （见 StreamingParseBenchmarkTest），真正贵的是每次发布后 Compose 给最后一条
 * 重排一遍 —— 换行、CJK 断字、整段 MultiParagraph 重建，而这部分随正文变长而变贵。
 * 固定 20Hz 在长输出上就是每帧重排几千字。
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
    fun neverSlowerThanFiveHertz() {
        // 再慢就肉眼看得见"一跳一跳"了。250ms = 4Hz，是硬上限。
        assertTrue(streamPublishIntervalMs(100_000) <= 250L)
        assertTrue(streamPublishIntervalMs(4000) <= 250L)
    }

    @Test
    fun longOutputSettlesWellUnderTwentyHertz() {
        // 一篇长回答（4000 字）应该退到 10Hz 以下，而不是还在 20Hz 上硬撑
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
