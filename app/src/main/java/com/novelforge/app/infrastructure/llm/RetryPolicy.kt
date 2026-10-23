package com.novelforge.app.infrastructure.llm

import kotlin.math.min

data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 8_000,
    val jitter: Boolean = true,
    /** 服务商明确说了 Retry-After 时允许的上限。它和本地退避上限不是一回事。 */
    val maxRetryAfterMs: Long = 120_000
) {
    fun shouldRetry(statusCode: Int, retryCount: Int): Boolean =
        retryCount < maxRetries && statusCode in RETRYABLE_STATUS_CODES

    fun delayMs(retryCount: Int, retryAfterSeconds: Long? = null): Long {
        if (retryAfterSeconds != null) {
            // 服务商说「60 秒后额度恢复」，就必须等 60 秒。
            // 以前这里被 maxDelayMs(8s) 截断，于是 8 秒后准时再撞一次 429，
            // 三次重试在 24 秒内耗光，然后整章失败。
            return (retryAfterSeconds * 1_000L).coerceIn(0L, maxRetryAfterMs)
        }
        val exponential = initialDelayMs * (1L shl retryCount.coerceIn(0, 10))
        val capped = min(exponential, maxDelayMs)
        if (!jitter) return capped
        // 抖动只向下取，不能突破上限：原来是 (0.8 + rand*0.4)，最坏会到上限的 1.2 倍
        return (capped * (0.5 + Math.random() * 0.5)).toLong()
    }

    companion object {
        private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
