package com.example.analytics.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

internal const val DEFAULT_DESTINATION = "default"

/**
 * One queue database file. Each process writes its own file (see [DatabaseFiles]) so processes never
 * contend on writes; the main process's upload worker also opens other processes' files to sweep
 * them, which SQLite's file locking makes safe. WAL mode keeps the writer (track) and the uploader
 * (claim/ack) from blocking each other.
 *
 * Every destination has its own rows (an event sent to two destinations is stored twice), so each
 * destination is acked, retried, expired and evicted independently.
 *
 * Instances are shared per file through [open]; one connection pool per file per process.
 */
internal class QueueDatabase private constructor(private val context: Context, val name: String) {

    private val helper = Helper(context, name)
    val db: SQLiteDatabase get() = helper.writableDatabase

    /** Removes rows of destinations no longer configured; they would otherwise sit there forever. */
    fun deleteDestinationsExcept(keep: Collection<String>): Int {
        val placeholders = keep.joinToString(",") { "?" }
        return db.delete(TABLE, "$C_DESTINATION NOT IN ($placeholders)", keep.toTypedArray())
    }

    fun clearAll() {
        db.delete(TABLE, null, null)
    }

    /** Corruption recovery: drop the file; the next access recreates it empty. */
    fun deleteFile() {
        synchronized(this) {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    private class Helper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, SCHEMA_VERSION) {

        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            createSchema(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // One step per version. Never drop data: queued events must survive SDK upgrades.
            if (oldVersion < 2) migrateToV2(db)
        }

        /**
         * v2 adds the destination column and widens the uniqueness constraint to
         * (destination, event_id). SQLite can't alter constraints, so the table is rebuilt; existing
         * rows (including in-flight ones) move to the default destination unchanged.
         */
        private fun migrateToV2(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE $TABLE RENAME TO ${TABLE}_v1")
            db.execSQL("DROP INDEX IF EXISTS idx_${TABLE}_status_id")
            db.execSQL("DROP INDEX IF EXISTS idx_${TABLE}_claim")
            createSchema(db)
            db.execSQL(
                "INSERT INTO $TABLE ($C_ID, $C_DESTINATION, $C_EVENT_ID, $C_PAYLOAD, $C_PRIORITY, $C_CREATED_AT, " +
                    "$C_STATUS, $C_CLAIM_TOKEN, $C_CLAIMED_AT, $C_ATTEMPTS) " +
                    "SELECT $C_ID, '$DEFAULT_DESTINATION', $C_EVENT_ID, $C_PAYLOAD, $C_PRIORITY, $C_CREATED_AT, " +
                    "$C_STATUS, $C_CLAIM_TOKEN, $C_CLAIMED_AT, $C_ATTEMPTS FROM ${TABLE}_v1",
            )
            db.execSQL("DROP TABLE ${TABLE}_v1")
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "$C_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$C_DESTINATION TEXT NOT NULL DEFAULT '$DEFAULT_DESTINATION', " +
                    "$C_EVENT_ID TEXT NOT NULL, " +
                    "$C_PAYLOAD TEXT NOT NULL, " +
                    "$C_PRIORITY INTEGER NOT NULL DEFAULT 0, " +
                    "$C_CREATED_AT INTEGER NOT NULL, " +
                    "$C_STATUS INTEGER NOT NULL DEFAULT $STATUS_PENDING, " +
                    "$C_CLAIM_TOKEN TEXT, " +
                    "$C_CLAIMED_AT INTEGER, " +
                    "$C_ATTEMPTS INTEGER NOT NULL DEFAULT 0, " +
                    "UNIQUE ($C_DESTINATION, $C_EVENT_ID))",
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_dest_status_id ON $TABLE ($C_DESTINATION, $C_STATUS, $C_ID)")
            db.execSQL("CREATE INDEX idx_${TABLE}_claim ON $TABLE ($C_CLAIM_TOKEN)")
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val TABLE = "events"
        const val C_ID = "id"
        const val C_DESTINATION = "destination"
        const val C_EVENT_ID = "event_id"
        const val C_PAYLOAD = "payload"
        const val C_PRIORITY = "priority"
        const val C_CREATED_AT = "created_at"
        const val C_STATUS = "status"
        const val C_CLAIM_TOKEN = "claim_token"
        const val C_CLAIMED_AT = "claimed_at"
        const val C_ATTEMPTS = "attempts"
        const val STATUS_PENDING = 0
        const val STATUS_IN_FLIGHT = 1

        private val open = HashMap<String, QueueDatabase>()

        fun open(context: Context, name: String): QueueDatabase = synchronized(open) {
            open.getOrPut(name) { QueueDatabase(context.applicationContext, name) }
        }

        /** Test hook: really close files so the next [open] reads them from disk again. */
        fun closeAll() = synchronized(open) {
            open.values.forEach { it.helper.close() }
            open.clear()
        }
    }
}

/** One destination's view of a [QueueDatabase]. */
internal class SqliteEventStore(
    private val database: QueueDatabase,
    private val destination: String = DEFAULT_DESTINATION,
) : EventStore {

    constructor(context: Context, databaseName: String, destination: String = DEFAULT_DESTINATION) :
        this(QueueDatabase.open(context, databaseName), destination)

    private val db: SQLiteDatabase get() = database.db

    override fun insert(events: List<PendingEvent>) {
        if (events.isEmpty()) return
        val db = db
        db.beginTransaction()
        try {
            val statement = db.compileStatement(
                "INSERT OR IGNORE INTO $TABLE ($C_DESTINATION, $C_EVENT_ID, $C_PAYLOAD, $C_PRIORITY, " +
                    "$C_CREATED_AT, $C_STATUS, $C_ATTEMPTS) VALUES (?, ?, ?, ?, ?, $STATUS_PENDING, 0)",
            )
            statement.use {
                for (event in events) {
                    it.clearBindings()
                    it.bindString(1, destination)
                    it.bindString(2, event.eventId)
                    it.bindString(3, event.payload)
                    it.bindLong(4, event.priority.toLong())
                    it.bindLong(5, event.createdAt)
                    it.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun claim(limit: Int, now: Long, leaseMillis: Long): ClaimedBatch? {
        val token = UUID.randomUUID().toString()
        val db = db
        // Android's beginTransaction() is BEGIN EXCLUSIVE: two processes can't claim the same rows.
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE $TABLE SET $C_STATUS = $STATUS_IN_FLIGHT, $C_CLAIM_TOKEN = ?, $C_CLAIMED_AT = ? " +
                    "WHERE $C_ID IN (SELECT $C_ID FROM $TABLE WHERE $C_DESTINATION = ? AND ($C_STATUS = $STATUS_PENDING " +
                    "OR ($C_STATUS = $STATUS_IN_FLIGHT AND $C_CLAIMED_AT < ?)) ORDER BY $C_ID LIMIT ?)",
                arrayOf<Any>(token, now, destination, now - leaseMillis, limit),
            )
            val events = ArrayList<StoredEvent>(limit)
            db.rawQuery(
                "SELECT $C_ID, $C_EVENT_ID, $C_PAYLOAD, $C_ATTEMPTS FROM $TABLE " +
                    "WHERE $C_CLAIM_TOKEN = ? ORDER BY $C_ID",
                arrayOf(token),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    events += StoredEvent(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3))
                }
            }
            db.setTransactionSuccessful()
            return if (events.isEmpty()) null else ClaimedBatch(token, events)
        } finally {
            db.endTransaction()
        }
    }

    // Claim tokens are unique, so ack/release need no destination filter.
    override fun ack(token: String, rowIds: Collection<Long>) {
        forEachChunk(rowIds) { placeholders, args ->
            db.execSQL(
                "DELETE FROM $TABLE WHERE $C_CLAIM_TOKEN = ? AND $C_ID IN ($placeholders)",
                arrayOf<Any>(token, *args),
            )
        }
    }

    override fun release(token: String, rowIds: Collection<Long>, countAttempt: Boolean) {
        val increment = if (countAttempt) 1 else 0
        forEachChunk(rowIds) { placeholders, args ->
            db.execSQL(
                "UPDATE $TABLE SET $C_STATUS = $STATUS_PENDING, $C_CLAIM_TOKEN = NULL, $C_CLAIMED_AT = NULL, " +
                    "$C_ATTEMPTS = $C_ATTEMPTS + $increment WHERE $C_CLAIM_TOKEN = ? AND $C_ID IN ($placeholders)",
                arrayOf<Any>(token, *args),
            )
        }
    }

    override fun deleteExpired(createdBefore: Long): Int = db.delete(
        TABLE,
        "$C_DESTINATION = ? AND $C_CREATED_AT < ? AND $C_STATUS = $STATUS_PENDING",
        arrayOf(destination, createdBefore.toString()),
    )

    override fun deleteExhausted(maxAttempts: Int): Int = db.delete(
        TABLE,
        "$C_DESTINATION = ? AND $C_ATTEMPTS >= ? AND $C_STATUS = $STATUS_PENDING",
        arrayOf(destination, maxAttempts.toString()),
    )

    override fun trimTo(maxSize: Int): Int {
        val overflow = count() - maxSize
        if (overflow <= 0) return 0
        return db.delete(
            TABLE,
            "$C_ID IN (SELECT $C_ID FROM $TABLE WHERE $C_DESTINATION = ? AND $C_STATUS = $STATUS_PENDING " +
                "ORDER BY $C_PRIORITY ASC, $C_ID ASC LIMIT ?)",
            arrayOf(destination, overflow.toString()),
        )
    }

    override fun count(): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE WHERE $C_DESTINATION = ?", arrayOf(destination))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    override fun clear() {
        db.delete(TABLE, "$C_DESTINATION = ?", arrayOf(destination))
    }

    /** The connection is shared by every destination of the file and stays open for the process. */
    override fun close() = Unit

    /** SQLite on older Android caps bound variables at 999; ids are chunked well below that. */
    private inline fun forEachChunk(rowIds: Collection<Long>, block: (String, Array<Any>) -> Unit) {
        rowIds.chunked(MAX_IDS_PER_STATEMENT).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            block(placeholders, chunk.toTypedArray<Any>())
        }
    }

    private companion object {
        const val TABLE = QueueDatabase.TABLE
        const val C_ID = QueueDatabase.C_ID
        const val C_DESTINATION = QueueDatabase.C_DESTINATION
        const val C_EVENT_ID = QueueDatabase.C_EVENT_ID
        const val C_PAYLOAD = QueueDatabase.C_PAYLOAD
        const val C_PRIORITY = QueueDatabase.C_PRIORITY
        const val C_CREATED_AT = QueueDatabase.C_CREATED_AT
        const val C_STATUS = QueueDatabase.C_STATUS
        const val C_CLAIM_TOKEN = QueueDatabase.C_CLAIM_TOKEN
        const val C_CLAIMED_AT = QueueDatabase.C_CLAIMED_AT
        const val C_ATTEMPTS = QueueDatabase.C_ATTEMPTS
        const val STATUS_PENDING = QueueDatabase.STATUS_PENDING
        const val STATUS_IN_FLIGHT = QueueDatabase.STATUS_IN_FLIGHT
        const val MAX_IDS_PER_STATEMENT = 400
    }
}
