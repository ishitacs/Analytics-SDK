package com.example.analytics

import android.content.Context
import android.os.SystemClock
import com.example.analytics.internal.AnalyticsClient
import com.example.analytics.internal.Command

/**
 * Entry point. Call [init] from `Application.onCreate` — in every process that tracks events,
 * and in the main process so background uploads can find the configuration.
 *
 * Every method is safe to call from any thread, never blocks, and never throws. Calls made before
 * [init] are buffered (up to [MAX_PRE_INIT_EVENTS]) and replayed once it runs.
 */
object Analytics {

    private const val MAX_PRE_INIT_EVENTS = 1_000

    @Volatile
    private var client: AnalyticsClient? = null
    private val lock = Any()
    private val preInitBuffer = ArrayList<Command>()

    @JvmStatic
    fun init(context: Context, config: AnalyticsConfig) {
        synchronized(lock) {
            if (client != null) {
                config.logger.warn("Analytics.init called more than once; ignoring")
                return
            }
            try {
                val created = AnalyticsClient(context.applicationContext, config)
                preInitBuffer.forEach(created::enqueue)
                preInitBuffer.clear()
                created.start()
                client = created
            } catch (e: Exception) {
                config.logger.error("Analytics failed to initialize", e)
            }
        }
    }

    @JvmStatic
    @JvmOverloads
    fun track(
        name: String,
        properties: Map<String, Any?> = emptyMap(),
        priority: EventPriority = EventPriority.NORMAL,
    ) = record(name, EventType.CUSTOM, properties, priority)

    @JvmStatic
    @JvmOverloads
    fun trackClick(name: String, properties: Map<String, Any?> = emptyMap()) =
        record(name, EventType.CLICK, properties, EventPriority.NORMAL)

    /** Prefer the `analytics-compose` / `analytics-views` helpers, which handle visibility and dedupe. */
    @JvmStatic
    @JvmOverloads
    fun trackImpression(name: String, itemKey: String? = null, properties: Map<String, Any?> = emptyMap()) =
        record(
            name,
            EventType.IMPRESSION,
            if (itemKey == null) properties else properties + ("item_key" to itemKey),
            EventPriority.NORMAL,
        )

    @JvmStatic
    @JvmOverloads
    fun screen(name: String, properties: Map<String, Any?> = emptyMap()) =
        record(name, EventType.SCREEN, properties, EventPriority.NORMAL)

    /**
     * Events tracked after this call carry [userId]; earlier ones keep the previous identity.
     * Emits an `identify` event (with [traits] as properties) so the backend can link the
     * anonymous history to the user. Switching from one user to another starts a new session.
     */
    @JvmStatic
    @JvmOverloads
    fun identify(userId: String, traits: Map<String, Any?> = emptyMap()) = submit {
        Command.Identify(userId, HashMap(traits), System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }

    /** Call on logout: clears user id and super properties, rotates the anonymous id, starts a new session. */
    @JvmStatic
    fun reset() = submit { Command.Reset(System.currentTimeMillis()) }

    /** Merged into every subsequent event's properties (per process, not persisted). */
    @JvmStatic
    fun setSuperProperties(properties: Map<String, Any?>) = submit { Command.SetSuperProperties(HashMap(properties)) }

    @JvmStatic
    fun removeSuperProperty(key: String) = submit { Command.RemoveSuperProperty(key) }

    /** Persisted across launches and processes. Opting out purges every queued event. */
    @JvmStatic
    fun optOut(optOut: Boolean) = submit { Command.SetOptOut(optOut) }

    /**
     * Upload now, ignoring the batch size, flush interval and client-side backoff. Includes every
     * event tracked before this call (it is ordered behind them on the writer).
     */
    @JvmStatic
    fun flush() = submit { Command.Flush }

    /**
     * Fetch remote config now instead of waiting for its refresh interval (e.g. after login, if
     * the config endpoint varies by user). No-op without [AnalyticsConfig.Builder.remoteConfigUrl].
     */
    @JvmStatic
    fun refreshRemoteConfig() {
        client?.refreshRemoteConfig()
    }

    internal fun clientOrNull(): AnalyticsClient? = client

    /** Test hook. */
    internal fun resetForTests() {
        synchronized(lock) {
            client?.shutdown()
            client = null
            preInitBuffer.clear()
        }
    }

    private fun record(name: String, type: EventType, properties: Map<String, Any?>, priority: EventPriority) {
        // Timestamps are taken here, on the caller's thread, so buffering never skews them.
        val clientTs = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        submit { Command.Track(name, type, HashMap(properties), priority, clientTs, elapsed) }
    }

    private inline fun submit(createCommand: () -> Command) {
        try {
            val command = createCommand()
            client?.let {
                it.enqueue(command)
                return
            }
            synchronized(lock) {
                client?.let {
                    it.enqueue(command)
                    return
                }
                if (preInitBuffer.size < MAX_PRE_INIT_EVENTS) preInitBuffer += command
            }
        } catch (_: Exception) {
            // e.g. ConcurrentModificationException copying a map the caller is mutating.
        }
    }
}
