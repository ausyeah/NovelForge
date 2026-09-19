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
        assertEquals(500L, policy.delayMs(0, retryAfterSeconds = 1))
    }
}
