package com.example.analytics

/** Category of an event. Sent as the envelope's `type` field. */
enum class EventType(val wireName: String) {
    CUSTOM("custom"),
    CLICK("click"),
    IMPRESSION("impression"),
    SCREEN("screen"),

    /** Emitted by `identify()`: links `anonymous_id` to `user_id` and carries user traits. */
    IDENTIFY("identify"),

    /** Events the SDK emits about itself, e.g. `sdk_events_dropped`. */
    SYSTEM("system"),
}

/**
 * Decides what gets evicted first when the on-disk queue hits [AnalyticsConfig.maxQueueSize].
 * Upload order is always chronological regardless of priority.
 */
enum class EventPriority(internal val value: Int) {
    NORMAL(0),

    /** Purchases, sign-ups: evicted only after every NORMAL event is gone. */
    HIGH(1),
}

/**
 * The user-supplied part of an event, as seen by [AnalyticsConfig.beforeSend].
 * The SDK adds the reserved envelope fields (ids, timestamps, device context) afterwards,
 * so they cannot be altered or spoofed through this object.
 */
data class AnalyticsEvent(
    val name: String,
    val type: EventType,
    val properties: Map<String, Any?>,
)
