package com.novelforge.app.infrastructure.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryPolicyTest {
    private val policy = RetryPolicy(maxRetries = 3, initialDelayMs = 100, maxDelayMs = 500, jitter = false)

    @Test
    fun retryableStatusesAreBounded() {
        assertTrue(policy.shouldRetry(429, 0))
        assertTrue(policy.shouldRetry(503, 2))
        assertFalse(policy.shouldRetry(503, 3))
        assertFalse(policy.shouldRetry(401, 0))
    }

    @Test
    fun delayUsesExponentialBackoffAndRetryAfter() {
        assertEquals(100L, policy.delayMs(0))
        assertEquals(200L, policy.delayMs(1))
        assertEquals(500L, policy.delayMs(8))
        // Retry-After 是服务商明确说的「额度什么时候恢复」，必须照做。
        // 以前这里被本地退避上限（500ms）截断，于是 0.5 秒后准时再撞一次 429，
        // 三次重试在 1.5 秒内耗光，然后整章失败。
        assertEquals(1_000L, policy.delayMs(0, retryAfterSeconds = 1))
    }

    @Test
    fun retryAfterIsCappedSeparatelyFromLocalBackoff() {
        val generous = RetryPolicy(maxRetries = 3, initialDelayMs = 100, maxDelayMs = 500, maxRetryAfterMs = 120_000)
        // 服务商说等 60 秒就等 60 秒
        assertEquals(60_000L, generous.delayMs(0, retryAfterSeconds = 60))
        // 但也不能无限等，有个独立的、宽松的上限
        assertEquals(120_000L, generous.delayMs(0, retryAfterSeconds = 3_600))
    }

    @Test
    fun jitterNeverExceedsTheCap() {
        val jittered = RetryPolicy(maxRetries = 3, initialDelayMs = 1_000, maxDelayMs = 8_000, jitter = true)
        repeat(200) {
            assertTrue(
                "抖动不能突破上限：${jittered.delayMs(8)}",
                jittered.delayMs(8) <= 8_000
            )
        }
    }
}
