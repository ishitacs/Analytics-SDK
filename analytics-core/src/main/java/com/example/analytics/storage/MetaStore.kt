package com.example.analytics.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Small key-value store shared by all of the app's processes (identity, opt-out, sequence counter).
 * SQLite rather than SharedPreferences because SharedPreferences is not multi-process safe.
 */
internal class MetaStore(context: Context) {

    private val helper = Helper(context.applicationContext)
    private val db: SQLiteDatabase get() = helper.writableDatabase

    fun get(key: String): String? =
        db.rawQuery("SELECT value FROM kv WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun put(key: String, value: String?) {
        if (value == null) {
            db.delete("kv", "key = ?", arrayOf(key))
        } else {
            db.execSQL("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)", arrayOf(key, value))
        }
    }

    /** Atomically stores [value] unless a value exists; returns whichever value won. */
    fun putIfAbsent(key: String, value: String): String {
        db.execSQL("INSERT OR IGNORE INTO kv (key, value) VALUES (?, ?)", arrayOf(key, value))
        return get(key) ?: value
    }

    /**
     * Reserves [size] consecutive sequence numbers across processes; returns the first one.
     * Handing out blocks keeps this to one write per [size] events.
     */
    fun reserveSequence(key: String, size: Int): Long {
        val database = db
        database.beginTransaction()
        try {
            val start = get(key)?.toLongOrNull() ?: 1L
            put(key, (start + size).toString())
            database.setTransactionSuccessful()
            return start
        } finally {
            database.endTransaction()
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DatabaseFiles.META, null, 1) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
