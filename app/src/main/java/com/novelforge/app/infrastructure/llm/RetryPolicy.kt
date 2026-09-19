package com.novelforge.app.infrastructure.llm

import kotlin.math.min

data class RetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1_000,
    val maxDelayMs: Long = 8_000,
    val jitter: Boolean = true
) {
    fun shouldRetry(statusCode: Int, retryCount: Int): Boolean =
        retryCount < maxRetries && statusCode in RETRYABLE_STATUS_CODES

    fun delayMs(retryCount: Int, retryAfterSeconds: Long? = null): Long {
        if (retryAfterSeconds != null) return (retryAfterSeconds * 1_000L).coerceAtMost(maxDelayMs)
        val exponential = initialDelayMs * (1L shl retryCount.coerceIn(0, 10))
        val capped = min(exponential, maxDelayMs)
        if (!jitter) return capped
        return (capped * (0.8 + Math.random() * 0.4)).toLong()
    }

    companion object {
        private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
