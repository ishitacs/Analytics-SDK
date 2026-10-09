package com.example.analytics.upload

import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun interface RetryPolicy {
    /**
     * @param consecutiveFailures 1 for the first failure in a row.
     * @param retryAfterMillis server-requested delay (`Retry-After`), if any.
     */
    fun delayMillis(consecutiveFailures: Int, retryAfterMillis: Long?): Long
}

/**
 * Exponential backoff with "equal jitter": half the delay is fixed, half is random. The jitter
 * matters at scale — without it every device that saw the same outage retries in lockstep and
 * knocks the backend over again the moment it recovers.
 *
 * A server `Retry-After` is honoured as a lower bound (capped at [maxRetryAfter]).
 */
class ExponentialBackoff(
    private val baseDelay: Duration = 1.seconds,
    private val maxDelay: Duration = 10.minutes,
    private val multiplier: Double = 2.0,
    private val jitter: Boolean = true,
    private val maxRetryAfter: Duration = 1.hours,
    private val random: Random = Random.Default,
) : RetryPolicy {

    init {
        require(baseDelay.isPositive()) { "baseDelay must be positive" }
        require(maxDelay >= baseDelay) { "maxDelay must be >= baseDelay" }
        require(multiplier >= 1.0) { "multiplier must be >= 1" }
    }

    override fun delayMillis(consecutiveFailures: Int, retryAfterMillis: Long?): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 30)
        val raw = baseDelay.inWholeMilliseconds * multiplier.pow(exponent)
        val capped = min(raw, maxDelay.inWholeMilliseconds.toDouble()).toLong()
        val computed = if (jitter) capped / 2 + random.nextLong(capped / 2 + 1) else capped
        val serverFloor = retryAfterMillis?.coerceIn(0, maxRetryAfter.inWholeMilliseconds) ?: 0
        return maxOf(computed, serverFloor)
    }
}
