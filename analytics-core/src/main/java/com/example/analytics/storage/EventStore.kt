package com.example.analytics.storage

internal class PendingEvent(
    val eventId: String,
    val payload: String,
    val priority: Int,
    val createdAt: Long,
)

internal class StoredEvent(
    val rowId: Long,
    val eventId: String,
    val payload: String,
    val attempts: Int,
)

/**
 * Rows leased to one uploader. [token] guards every ack/release, so if a lease expires and another
 * process re-claims the rows, a late ack from the original holder can't delete them twice.
 */
internal class ClaimedBatch(val token: String, val events: List<StoredEvent>)

/**
 * Durable FIFO queue of serialized event envelopes.
 *
 * Delivery is at-least-once: rows move PENDING -> IN_FLIGHT (claimed) -> deleted (acked).
 * A crashed or killed uploader leaves rows IN_FLIGHT; they become claimable again after the lease
 * expires, and the backend dedupes the resend by `event_id`.
 */
internal interface EventStore {
    /** Duplicate event ids are ignored. */
    fun insert(events: List<PendingEvent>)

    /** Leases up to [limit] oldest claimable rows, or null if there are none. */
    fun claim(limit: Int, now: Long, leaseMillis: Long): ClaimedBatch?

    /** Deletes delivered (or permanently rejected) rows of a claim. */
    fun ack(token: String, rowIds: Collection<Long>)

    /** Returns rows of a claim to PENDING. */
    fun release(token: String, rowIds: Collection<Long>, countAttempt: Boolean)

    /** @return rows deleted. */
    fun deleteExpired(createdBefore: Long): Int

    /** @return rows deleted. */
    fun deleteExhausted(maxAttempts: Int): Int

    /** Evicts PENDING rows, lowest priority then oldest first, until at most [maxSize] remain. */
    fun trimTo(maxSize: Int): Int

    fun count(): Int

    fun clear()

    fun close()
}
