package com.example.analytics.storage

import java.util.UUID

/**
 * Non-durable [EventStore] with the same semantics as [SqliteEventStore]. Used as the fallback when
 * the disk is unusable (full, corrupt, read-only) and in tests.
 */
internal class InMemoryEventStore(private val capacity: Int = Int.MAX_VALUE) : EventStore {

    private class Row(
        val rowId: Long,
        val event: PendingEvent,
        var attempts: Int = 0,
        var claimToken: String? = null,
        var claimedAt: Long = 0,
    )

    private val rows = ArrayList<Row>()
    private val eventIds = HashSet<String>()
    private var nextId = 1L

    @Synchronized
    override fun insert(events: List<PendingEvent>) {
        for (event in events) {
            if (!eventIds.add(event.eventId)) continue
            rows += Row(nextId++, event)
        }
        trimTo(capacity)
    }

    @Synchronized
    override fun claim(limit: Int, now: Long, leaseMillis: Long): ClaimedBatch? {
        val token = UUID.randomUUID().toString()
        val claimed = rows.asSequence()
            .filter { it.claimToken == null || it.claimedAt < now - leaseMillis }
            .take(limit)
            .onEach {
                it.claimToken = token
                it.claimedAt = now
            }
            .map { StoredEvent(it.rowId, it.event.eventId, it.event.payload, it.attempts) }
            .toList()
        return if (claimed.isEmpty()) null else ClaimedBatch(token, claimed)
    }

    @Synchronized
    override fun ack(token: String, rowIds: Collection<Long>) {
        val ids = rowIds.toHashSet()
        removeWhere { it.claimToken == token && it.rowId in ids }
    }

    @Synchronized
    override fun release(token: String, rowIds: Collection<Long>, countAttempt: Boolean) {
        val ids = rowIds.toHashSet()
        rows.filter { it.claimToken == token && it.rowId in ids }.forEach {
            it.claimToken = null
            if (countAttempt) it.attempts++
        }
    }

    @Synchronized
    override fun deleteExpired(createdBefore: Long): Int =
        removeWhere { it.claimToken == null && it.event.createdAt < createdBefore }

    @Synchronized
    override fun deleteExhausted(maxAttempts: Int): Int =
        removeWhere { it.claimToken == null && it.attempts >= maxAttempts }

    @Synchronized
    override fun trimTo(maxSize: Int): Int {
        val overflow = rows.size - maxSize
        if (overflow <= 0) return 0
        val victims = rows.filter { it.claimToken == null }
            .sortedWith(compareBy<Row> { it.event.priority }.thenBy { it.rowId })
            .take(overflow)
            .map { it.rowId }
            .toHashSet()
        return removeWhere { it.rowId in victims }
    }

    @Synchronized
    override fun count(): Int = rows.size

    @Synchronized
    override fun clear() {
        rows.clear()
        eventIds.clear()
    }

    override fun close() = Unit

    private fun removeWhere(predicate: (Row) -> Boolean): Int {
        val removed = rows.filter(predicate)
        rows.removeAll(removed.toSet())
        removed.forEach { eventIds.remove(it.event.eventId) }
        return removed.size
    }
}
