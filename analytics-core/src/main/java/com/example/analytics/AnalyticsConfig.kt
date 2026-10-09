package com.example.analytics

import com.example.analytics.network.DefaultJsonSerializer
import com.example.analytics.network.DefaultNetworkClient
import com.example.analytics.network.DefaultResponseClassifier
import com.example.analytics.network.EventSerializer
import com.example.analytics.network.NetworkClient
import com.example.analytics.network.ResponseClassifier
import com.example.analytics.upload.ExponentialBackoff
import com.example.analytics.upload.RetryPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Guards against oversized or pathological property maps. */
class PropertyLimits(
    val maxNameLength: Int = 128,
    val maxKeyLength: Int = 128,
    val maxStringLength: Int = 1024,
    val maxDepth: Int = 5,
    val maxProperties: Int = 200,
    val maxCollectionSize: Int = 100,
    val maxEventBytes: Int = 32 * 1024,
)

/**
 * Build with the [AnalyticsConfig] factory function:
 * ```
 * AnalyticsConfig("https://api.myapp.com/v1/events") {
 *     header("X-App-Key", "...")
 *     headerProvider = { mapOf("Authorization" to "Bearer ${tokens.current()}") }
 *     batchSize = 50
 * }
 * ```
 * Invalid values fail fast in [Builder.build] — that is an integration bug, surfaced at init.
 */
class AnalyticsConfig private constructor(builder: Builder) {
    val endpoint: String = builder.endpoint
    val httpMethod: String = builder.httpMethod
    val headers: Map<String, String> = builder.headers.toMap()
    val headerProvider: (() -> Map<String, String>)? = builder.headerProvider
    val batchSize: Int = builder.batchSize
    val flushInterval: Duration = builder.flushInterval
    val maxQueueSize: Int = builder.maxQueueSize
    val eventTtl: Duration = builder.eventTtl
    val maxAttempts: Int = builder.maxAttempts
    val gzip: Boolean = builder.gzip
    val serializer: EventSerializer = builder.serializer
    val networkClient: NetworkClient = builder.networkClient
    val retryPolicy: RetryPolicy = builder.retryPolicy
    val responseClassifier: ResponseClassifier = builder.responseClassifier
    val propertyLimits: PropertyLimits = builder.propertyLimits
    val beforeSend: ((AnalyticsEvent) -> AnalyticsEvent?)? = builder.beforeSend
    val listener: AnalyticsListener? = builder.listener
    val debug: Boolean = builder.debug
    val logger: AnalyticsLogger = builder.logger ?: LogcatLogger(builder.debug)
    val backgroundUploads: Boolean = builder.backgroundUploads
    val sessionTimeout: Duration = builder.sessionTimeout
    val destinations: List<Destination> = builder.destinations.toList()
    val remoteConfigUrl: String? = builder.remoteConfigUrl
    val remoteConfigRefreshInterval: Duration = builder.remoteConfigRefreshInterval

    /** The main endpoint as a destination, followed by [destinations]. */
    internal val allDestinations: List<Destination> =
        listOf(
            Destination.Builder(Destination.DEFAULT_ID, endpoint).apply {
                httpMethod = builder.httpMethod
                builder.headers.forEach { (name, value) -> header(name, value) }
                headerProvider = builder.headerProvider
                batchSize = builder.batchSize
                gzip = builder.gzip
                serializer = builder.serializer
                networkClient = builder.networkClient
                retryPolicy = builder.retryPolicy
                responseClassifier = builder.responseClassifier
            }.buildDefault(),
        ) + destinations
    val newSessionOnColdStart: Boolean = builder.newSessionOnColdStart

    class Builder(val endpoint: String) {
        var httpMethod: String = "POST"
        internal val headers = linkedMapOf<String, String>()

        /** Evaluated on every request, so rotating auth tokens are always fresh. */
        var headerProvider: (() -> Map<String, String>)? = null

        /** Events per request, and the queue depth that triggers an immediate flush. */
        var batchSize: Int = 50

        /** Upload cadence while the app process is alive. */
        var flushInterval: Duration = 30.seconds

        /** Max events kept on disk per process; oldest NORMAL-priority events are evicted first. */
        var maxQueueSize: Int = 10_000

        /** Undelivered events older than this are discarded. */
        var eventTtl: Duration = 7.days

        /** Server-side failures (5xx/429/...) before an event is dropped. Network errors don't count. */
        var maxAttempts: Int = 25

        var gzip: Boolean = true
        var serializer: EventSerializer = DefaultJsonSerializer()
        var networkClient: NetworkClient = DefaultNetworkClient()
        var retryPolicy: RetryPolicy = ExponentialBackoff(maxDelay = 10.minutes)
        var responseClassifier: ResponseClassifier = DefaultResponseClassifier()
        var propertyLimits: PropertyLimits = PropertyLimits()

        /**
         * Last chance to scrub PII or filter an event; return null to drop it.
         * Runs on the SDK writer thread. Reserved envelope fields are added after this hook.
         */
        var beforeSend: ((AnalyticsEvent) -> AnalyticsEvent?)? = null

        var listener: AnalyticsListener? = null
        var debug: Boolean = false
        var logger: AnalyticsLogger? = null

        /**
         * Schedule WorkManager jobs so queued events are delivered even if the app is killed or
         * never reopened. Turn off only if the host app manages WorkManager in a way that conflicts.
         */
        var backgroundUploads: Boolean = true

        /** Background inactivity after which the next event starts a new session. */
        var sessionTimeout: Duration = 30.minutes

        /**
         * Also start a new session whenever the main process cold-starts into the foreground.
         * Off by default because Android kills backgrounded processes often (especially on
         * low-RAM devices), which would split one real session into several.
         */
        var newSessionOnColdStart: Boolean = false

        internal val destinations = mutableListOf<Destination>()

        /**
         * GET endpoint for server-controlled settings (kill switches, sampling, blocked events,
         * batching). Fetched by the main process with the default destination's client and headers.
         * Null disables remote config.
         */
        var remoteConfigUrl: String? = null

        /** How often to re-fetch remote config (jittered ±10%); a response's `ttl_seconds` overrides it. */
        var remoteConfigRefreshInterval: Duration = 1.hours

        /** Also send events to another backend. See [Destination]. */
        fun destination(id: String, endpoint: String, block: Destination.Builder.() -> Unit = {}) {
            destinations += Destination(id, endpoint, block)
        }

        fun header(name: String, value: String) {
            headers[name] = value
        }

        fun build(): AnalyticsConfig {
            require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
                "endpoint must be an http(s) URL: $endpoint"
            }
            require(batchSize in 1..500) { "batchSize must be in 1..500" }
            require(maxQueueSize >= batchSize) { "maxQueueSize must be >= batchSize" }
            require(flushInterval >= 1.seconds) { "flushInterval must be >= 1s" }
            require(eventTtl.isPositive()) { "eventTtl must be positive" }
            require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
            require(sessionTimeout >= 1.minutes) { "sessionTimeout must be >= 1 minute" }
            require(destinations.map { it.id }.toSet().size == destinations.size) { "Destination ids must be unique" }
            remoteConfigUrl?.let {
                require(it.startsWith("https://") || it.startsWith("http://")) { "remoteConfigUrl must be an http(s) URL" }
            }
            require(remoteConfigRefreshInterval >= 1.minutes) { "remoteConfigRefreshInterval must be >= 1 minute" }
            return AnalyticsConfig(this)
        }
    }
}

fun AnalyticsConfig(endpoint: String, block: AnalyticsConfig.Builder.() -> Unit = {}): AnalyticsConfig =
    AnalyticsConfig.Builder(endpoint).apply(block).build()
