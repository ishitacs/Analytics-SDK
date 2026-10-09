package com.example.analytics.internal

import com.example.analytics.storage.MetaStore
import java.util.UUID

internal class SessionStart(val sessionId: String, val previousSessionId: String?, val reason: String)

/**
 * Session rules:
 * - A session expires after [timeoutMillis] without activity while the app is in the background.
 *   Events while this process is in the foreground never expire it (a user reading for 40 minutes
 *   is still in the same session).
 * - Identity change (switching users, or reset/logout) starts a new session.
 * - Optionally a cold start of the main process starts one ([newSessionOnColdStart]). Off by default:
 *   Android kills background processes routinely, which would otherwise inflate session counts.
 *
 * State lives in the cross-process [MetaStore] so all processes share one session. Only called from
 * the writer thread. Uses the wall clock because sessions must span process restarts and reboots.
 */
internal class SessionManager(
    private val meta: MetaStore,
    private val timeoutMillis: Long,
    private val newSessionOnColdStart: Boolean,
    private val clock: Clock,
    private val refreshIntervalMillis: Long = 5_000,
    private val persistIntervalMillis: Long = 15_000,
) {
    private var sessionId: String? = null
    private var lastActivity = 0L
    private var lastPersisted = 0L
    private var loadedAt: Long? = null
    private var inForeground = false
    private var coldStartPending = newSessionOnColdStart

    val currentSessionId: String? get() = sessionId

    /** Called for every event at its tracking time. Returns non-null if a new session began. */
    fun onEvent(now: Long): SessionStart? {
        refreshIfStale()
        val current = sessionId
        val start = when {
            current == null -> rotate(now, "first")
            !inForeground && now - lastActivity > timeoutMillis -> rotate(now, "timeout")
            else -> null
        }
        markActive(now)
        return start
    }

    fun onForeground(now: Long, isMainProcess: Boolean): SessionStart? {
        refreshIfStale()
        val start = when {
            coldStartPending && isMainProcess -> rotate(now, "cold_start")
            sessionId == null -> rotate(now, "first")
            now - lastActivity > timeoutMillis -> rotate(now, "timeout")
            else -> null
        }
        coldStartPending = false
        inForeground = true
        markActive(now)
        return start
    }

    fun onBackground(now: Long) {
        inForeground = false
        if (sessionId == null) return
        markActive(now)
        persist()
    }

    fun rotate(now: Long, reason: String): SessionStart {
        val previous = sessionId
        val next = UUID.randomUUID().toString()
        sessionId = next
        lastActivity = now
        persist()
        return SessionStart(next, previous, reason)
    }

    private fun markActive(now: Long) {
        // max(): the user moving the clock backwards must not "un-expire" anything.
        lastActivity = maxOf(lastActivity, now)
        if (now - lastPersisted >= persistIntervalMillis) persist()
    }

    private fun persist() {
        val id = sessionId ?: return
        meta.put(KEY_SESSION_ID, id)
        meta.put(KEY_LAST_ACTIVITY, lastActivity.toString())
        lastPersisted = lastActivity
    }

    /** Adopts a session another process started; merges activity so neither process expires it early. */
    private fun refreshIfStale() {
        val now = clock.elapsedRealtime()
        loadedAt?.let { if (now - it < refreshIntervalMillis) return }
        loadedAt = now
        val storedId = meta.get(KEY_SESSION_ID) ?: return
        val storedActivity = meta.get(KEY_LAST_ACTIVITY)?.toLongOrNull() ?: 0L
        if (storedId != sessionId) {
            sessionId = storedId
            lastActivity = storedActivity
        } else {
            lastActivity = maxOf(lastActivity, storedActivity)
        }
    }

    private companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_LAST_ACTIVITY = "session_last_activity"
    }
}
