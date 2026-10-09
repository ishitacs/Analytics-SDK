package com.example.analytics.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ExponentialBackoffTest {

    @Test
    fun `grows exponentially and caps at max`() {
        val policy = ExponentialBackoff(baseDelay = 1.seconds, maxDelay = 10.seconds, jitter = false)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map { policy.delayMillis(it, null) })
    }

    @Test
    fun `jitter stays within half to full delay`() {
        val policy = ExponentialBackoff(baseDelay = 1.seconds, maxDelay = 60.seconds, random = Random(42))
        repeat(100) {
            val delay = policy.delayMillis(4, null)
            assertTrue(delay in 4_000L..8_000L)
        }
    }

    @Test
    fun `retry-after is a floor and is capped`() {
        val policy = ExponentialBackoff(baseDelay = 1.seconds, jitter = false, maxRetryAfter = 1.hours)
        assertEquals(30_000L, policy.delayMillis(1, 30_000L))
        assertEquals(3_600_000L, policy.delayMillis(1, 10 * 3_600_000L))
    }

    @Test
    fun `huge failure counts do not overflow`() {
        val policy = ExponentialBackoff(jitter = false)
        assertEquals(600_000L, policy.delayMillis(Int.MAX_VALUE, null))
    }
}
