package com.example.analytics.internal

import com.example.analytics.EventPriority
import com.example.analytics.EventType

/**
 * Everything the public API does is funneled through one channel as a command, so the writer
 * applies them in call order: an event tracked before `identify()` keeps the old identity.
 */
internal sealed interface Command {
    /** Timestamps are captured on the caller's thread, at the moment `track()` is called. */
    class Track(
        val name: String,
        val type: EventType,
        val properties: Map<String, Any?>,
        val priority: EventPriority,
        val clientTs: Long,
        val elapsedRealtime: Long,
    ) : Command

    class Identify(val userId: String, val traits: Map<String, Any?>, val clientTs: Long, val elapsedRealtime: Long) :
        Command
    class Reset(val clientTs: Long) : Command
    class AppForeground(val clientTs: Long) : Command
    class AppBackground(val clientTs: Long) : Command
    class SetSuperProperties(val properties: Map<String, Any?>) : Command
    class RemoveSuperProperty(val key: String) : Command
    class SetOptOut(val optOut: Boolean) : Command

    /** Goes through the channel so everything tracked before `flush()` is on disk when the upload starts. */
    object Flush : Command
}
