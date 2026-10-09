package com.example.analytics.storage

import android.database.sqlite.SQLiteDatabaseCorruptException
import com.example.analytics.AnalyticsLogger

/**
 * Wraps the disk store so storage failures never reach the host app.
 *
 * - Disk full / locked / read-only: events go to a bounded in-memory [fallback], which is uploaded
 *   first (it's the data most at risk).
 * - Corruption: the database file is deleted and recreated; its contents are unrecoverable anyway.
 */
internal class ResilientEventStore(
    private val openPrimary: () -> EventStore,
    private val deletePrimary: () -> Unit,
    private val logger: AnalyticsLogger,
    fallbackCapacity: Int = 1_000,
) : EventStore {

    private val fallback = InMemoryEventStore(fallbackCapacity)
    private val fallbackTokens = HashSet<String>()

    @Volatile
    private var primary: EventStore? = null

    override fun insert(events: List<PendingEvent>) {
        val stored = onPrimary("insert", false) {
            it.insert(events)
            true
        }
        if (!stored) fallback.insert(events)
    }

    override fun claim(limit: Int, now: Long, leaseMillis: Long): ClaimedBatch? {
        fallback.claim(limit, now, leaseMillis)?.let { batch ->
            synchronized(fallbackTokens) { fallbackTokens += batch.token }
            return batch
        }
        return onPrimary("claim", null) { it.claim(limit, now, leaseMillis) }
    }

    override fun ack(token: String, rowIds: Collection<Long>) {
        if (isFallbackToken(token)) {
            fallback.ack(token, rowIds)
            forgetFallbackTokensIfDrained()
        } else {
            onPrimary("ack", Unit) { it.ack(token, rowIds) }
        }
    }

    override fun release(token: String, rowIds: Collection<Long>, countAttempt: Boolean) {
        if (isFallbackToken(token)) {
            fallback.release(token, rowIds, countAttempt)
        } else {
            onPrimary("release", Unit) { it.release(token, rowIds, countAttempt) }
        }
    }

    override fun deleteExpired(createdBefore: Long): Int =
        fallback.deleteExpired(createdBefore) + onPrimary("deleteExpired", 0) { it.deleteExpired(createdBefore) }

    override fun deleteExhausted(maxAttempts: Int): Int =
        fallback.deleteExhausted(maxAttempts) + onPrimary("deleteExhausted", 0) { it.deleteExhausted(maxAttempts) }

    override fun trimTo(maxSize: Int): Int = onPrimary("trim", 0) { it.trimTo(maxSize) }

    override fun count(): Int = fallback.count() + onPrimary("count", 0) { it.count() }

    override fun clear() {
        fallback.clear()
        onPrimary("clear", Unit) { it.clear() }
    }

    override fun close() {
        primary?.close()
        primary = null
    }

    private fun isFallbackToken(token: String) = synchronized(fallbackTokens) { token in fallbackTokens }

    private fun forgetFallbackTokensIfDrained() {
        if (fallback.count() == 0) synchronized(fallbackTokens) { fallbackTokens.clear() }
    }

    private inline fun <T> onPrimary(operation: String, default: T, block: (EventStore) -> T): T {
        return try {
            val store = primary ?: openPrimary().also { primary = it }
            block(store)
        } catch (e: SQLiteDatabaseCorruptException) {
            logger.error("Event database corrupt during $operation; recreating it", e)
            recreatePrimary()
            default
        } catch (e: Exception) {
            logger.warn("Event database $operation failed", e)
            default
        }
    }

    private fun recreatePrimary() {
        try {
            primary?.close()
        } catch (_: Exception) {
        }
        primary = null
        try {
            deletePrimary()
        } catch (e: Exception) {
            logger.error("Could not delete corrupt event database", e)
        }
    }
}
