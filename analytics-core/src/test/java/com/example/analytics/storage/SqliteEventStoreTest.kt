package com.example.analytics.storage

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SqliteEventStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: SqliteEventStore

    @Before
    fun setUp() {
        store = SqliteEventStore(context, "test_queue.db")
    }

    @After
    fun tearDown() {
        QueueDatabase.closeAll()
        context.deleteDatabase("test_queue.db")
    }

    private fun event(id: String, priority: Int = 0, createdAt: Long = 1_000) =
        PendingEvent(id, "{\"event_id\":\"$id\"}", priority, createdAt)

    @Test
    fun `duplicate event ids are ignored`() {
        store.insert(listOf(event("a"), event("a"), event("b")))
        assertEquals(2, store.count())
    }

    @Test
    fun `claim is FIFO and claimed rows are not claimed twice`() {
        store.insert((1..5).map { event("e$it") })
        val first = store.claim(3, now = 10_000, leaseMillis = 60_000)!!
        assertEquals(listOf("e1", "e2", "e3"), first.events.map { it.eventId })
        val second = store.claim(3, now = 10_000, leaseMillis = 60_000)!!
        assertEquals(listOf("e4", "e5"), second.events.map { it.eventId })
        assertNull(store.claim(3, now = 10_000, leaseMillis = 60_000))
    }

    @Test
    fun `ack deletes and release returns rows to the queue`() {
        store.insert((1..4).map { event("e$it") })
        val batch = store.claim(4, now = 10_000, leaseMillis = 60_000)!!
        store.ack(batch.token, batch.events.take(2).map { it.rowId })
        store.release(batch.token, batch.events.drop(2).map { it.rowId }, countAttempt = true)
        assertEquals(2, store.count())
        val again = store.claim(10, now = 10_000, leaseMillis = 60_000)!!
        assertEquals(listOf("e3", "e4"), again.events.map { it.eventId })
        assertEquals(listOf(1, 1), again.events.map { it.attempts })
    }

    @Test
    fun `expired lease makes orphaned rows claimable again`() {
        store.insert(listOf(event("a")))
        val orphan = store.claim(1, now = 10_000, leaseMillis = 1_000)!!
        assertNull(store.claim(1, now = 10_500, leaseMillis = 1_000))
        val reclaimed = store.claim(1, now = 11_001, leaseMillis = 1_000)
        assertNotNull(reclaimed)
        // A late ack from the original holder must not touch the re-claimed row.
        store.ack(orphan.token, orphan.events.map { it.rowId })
        assertEquals(1, store.count())
    }

    @Test
    fun `trim evicts low priority oldest first`() {
        store.insert(listOf(event("old-high", 1), event("old", 0), event("mid", 0), event("new", 0)))
        assertEquals(2, store.trimTo(2))
        val left = store.claim(10, now = 0, leaseMillis = 1)!!.events.map { it.eventId }
        assertEquals(listOf("old-high", "new"), left)
    }

    @Test
    fun `expired and exhausted rows are deleted`() {
        store.insert(listOf(event("stale", createdAt = 100), event("fresh", createdAt = 5_000)))
        assertEquals(1, store.deleteExpired(createdBefore = 1_000))
        val batch = store.claim(1, now = 0, leaseMillis = 1)!!
        store.release(batch.token, batch.events.map { it.rowId }, countAttempt = true)
        assertEquals(1, store.deleteExhausted(maxAttempts = 1))
        assertEquals(0, store.count())
    }

    @Test
    fun `events survive reopening the database`() {
        store.insert(listOf(event("persisted")))
        QueueDatabase.closeAll()
        store = SqliteEventStore(context, "test_queue.db")
        assertEquals(1, store.count())
    }

    @Test
    fun `release and ack handle more ids than SQLite's bind limit`() {
        store.insert((1..1200).map { event("e$it") })
        val batch = store.claim(1200, now = 0, leaseMillis = 60_000)!!
        store.ack(batch.token, batch.events.map { it.rowId })
        assertEquals(0, store.count())
    }

    @Test
    fun `destinations are isolated queues over one file`() {
        val warehouse = SqliteEventStore(context, "test_queue.db", "warehouse")
        store.insert(listOf(event("a"), event("b")))
        warehouse.insert(listOf(event("a"))) // same event id, different destination: both kept
        assertEquals(2, store.count())
        assertEquals(1, warehouse.count())

        val batch = warehouse.claim(10, now = 0, leaseMillis = 60_000)!!
        assertEquals(listOf("a"), batch.events.map { it.eventId })
        warehouse.ack(batch.token, batch.events.map { it.rowId })
        assertEquals(0, warehouse.count())
        assertEquals(2, store.count())

        warehouse.insert(listOf(event("c")))
        warehouse.clear()
        assertEquals(2, store.count())
    }

    @Test
    fun `rows of removed destinations are cleaned up`() {
        SqliteEventStore(context, "test_queue.db", "old").insert(listOf(event("x")))
        store.insert(listOf(event("y")))
        assertEquals(1, QueueDatabase.open(context, "test_queue.db").deleteDestinationsExcept(listOf("default")))
        assertEquals(1, store.count())
    }

    @Test
    fun `v1 database migrates to v2 keeping queued and in-flight rows`() {
        QueueDatabase.closeAll()
        context.deleteDatabase("test_queue.db")
        // Exactly the schema shipped in SDK 0.1 (schema version 1).
        val path = context.getDatabasePath("test_queue.db").apply { parentFile?.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT NOT NULL UNIQUE, " +
                    "payload TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, " +
                    "status INTEGER NOT NULL DEFAULT 0, claim_token TEXT, claimed_at INTEGER, attempts INTEGER NOT NULL DEFAULT 0)",
            )
            v1.execSQL("CREATE INDEX idx_events_status_id ON events (status, id)")
            v1.execSQL("CREATE INDEX idx_events_claim ON events (claim_token)")
            v1.execSQL("INSERT INTO events (event_id, payload, created_at, attempts) VALUES ('old1', '{}', 1000, 2)")
            v1.execSQL("INSERT INTO events (event_id, payload, created_at, status, claim_token, claimed_at) VALUES ('old2', '{}', 1000, 1, 't', 5)")
            v1.version = 1
        }

        store = SqliteEventStore(context, "test_queue.db")
        assertEquals(2, store.count())
        // The orphaned in-flight row is still reclaimable after the lease, attempts preserved.
        val batch = store.claim(10, now = 1_000_000, leaseMillis = 1_000)!!
        assertEquals(listOf("old1" to 2, "old2" to 0), batch.events.map { it.eventId to it.attempts })
        // New destinations work on the migrated file.
        SqliteEventStore(context, "test_queue.db", "warehouse").insert(listOf(event("old1")))
        assertEquals(1, SqliteEventStore(context, "test_queue.db", "warehouse").count())
    }
}
