package com.example.analytics.internal

import com.example.analytics.storage.MetaStore
import java.util.UUID

internal class Identity(val deviceId: String, val anonymousId: String, val userId: String?)

/**
 * Identity persisted in the cross-process [MetaStore].
 *
 * - `device_id`: stable per install, survives [reset].
 * - `anonymous_id`: rotated on [reset] (logout), so a new user isn't linked to the previous one.
 * - `user_id`: set by identify, cleared on reset.
 *
 * Only touched from the single writer thread. Other processes' changes are picked up within
 * [refreshIntervalMillis].
 */
internal class IdentityStore(
    private val meta: MetaStore,
    private val clock: Clock,
    private val refreshIntervalMillis: Long = 5_000,
) {
    private var cached: Identity? = null
    private var loadedAt = 0L

    fun current(): Identity {
        val now = clock.elapsedRealtime()
        cached?.let { if (now - loadedAt < refreshIntervalMillis) return it }
        return load().also {
            cached = it
            loadedAt = now
        }
    }

    fun identify(userId: String) {
        meta.put(KEY_USER_ID, userId)
        cached = null
    }

    fun reset() {
        meta.put(KEY_USER_ID, null)
        meta.put(KEY_ANONYMOUS_ID, UUID.randomUUID().toString())
        cached = null
    }

    private fun load(): Identity = Identity(
        deviceId = meta.putIfAbsent(KEY_DEVICE_ID, UUID.randomUUID().toString()),
        anonymousId = meta.putIfAbsent(KEY_ANONYMOUS_ID, UUID.randomUUID().toString()),
        userId = meta.get(KEY_USER_ID),
    )

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_ANONYMOUS_ID = "anonymous_id"
        const val KEY_USER_ID = "user_id"
    }
}
