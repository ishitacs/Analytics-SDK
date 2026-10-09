package com.example.analytics

/**
 * Keys owned by the SDK. A property using one of these names (case-insensitive) is dropped with a
 * warning, so host code can never overwrite identity, ordering or versioning fields — even on
 * backends that flatten `properties` into the top-level row.
 */
object ReservedKeys {
    const val EVENT_ID = "event_id"
    const val NAME = "name"
    const val TYPE = "type"
    const val PROPERTIES = "properties"
    const val USER_ID = "user_id"
    const val ANONYMOUS_ID = "anonymous_id"
    const val DEVICE_ID = "device_id"
    const val SESSION_ID = "session_id"
    const val SEQ = "seq"
    const val CLIENT_TS = "client_ts"
    const val ELAPSED_REALTIME = "elapsed_realtime"
    const val APP_VERSION = "app_version"
    const val SDK_VERSION = "sdk_version"
    const val OS_VERSION = "os_version"
    const val SCHEMA_VERSION = "schema_version"
    const val CONTEXT = "context"
    const val SENT_AT = "sent_at"
    const val BATCH_ID = "batch_id"

    val ALL: Set<String> = setOf(
        EVENT_ID, NAME, TYPE, PROPERTIES, USER_ID, ANONYMOUS_ID, DEVICE_ID, SESSION_ID, SEQ,
        CLIENT_TS, ELAPSED_REALTIME, APP_VERSION, SDK_VERSION, OS_VERSION, SCHEMA_VERSION,
        CONTEXT, SENT_AT, BATCH_ID,
    )

    fun isReserved(key: String): Boolean = key.lowercase() in ALL
}
