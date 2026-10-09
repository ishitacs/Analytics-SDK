package com.example.analytics

import com.example.analytics.network.DefaultJsonSerializer
import com.example.analytics.network.DefaultNetworkClient
import com.example.analytics.network.DefaultResponseClassifier
import com.example.analytics.network.EventSerializer
import com.example.analytics.network.NetworkClient
import com.example.analytics.network.ResponseClassifier
import com.example.analytics.upload.ExponentialBackoff
import com.example.analytics.upload.RetryPolicy

/**
 * An additional backend that receives (a filtered subset of) events, e.g. a data warehouse
 * collector next to the product-analytics API. The endpoint configured on [AnalyticsConfig] itself
 * is the `default` destination.
 *
 * Each destination has its own queue rows, batching, retries and backoff: one being down or slow
 * never delays another. A destination added later receives only events tracked after it was added.
 */
class Destination private constructor(builder: Builder) {
    val id: String = builder.id
    val endpoint: String = builder.endpoint
    val httpMethod: String = builder.httpMethod
    val headers: Map<String, String> = builder.headers.toMap()
    val headerProvider: (() -> Map<String, String>)? = builder.headerProvider
    val batchSize: Int = builder.batchSize
    val gzip: Boolean = builder.gzip
    val serializer: EventSerializer = builder.serializer
    val networkClient: NetworkClient = builder.networkClient
    val retryPolicy: RetryPolicy = builder.retryPolicy
    val responseClassifier: ResponseClassifier = builder.responseClassifier
    val filter: ((AnalyticsEvent) -> Boolean)? = builder.filter

    class Builder(val id: String, val endpoint: String) {
        var httpMethod: String = "POST"
        internal val headers = linkedMapOf<String, String>()
        var headerProvider: (() -> Map<String, String>)? = null
        var batchSize: Int = 50
        var gzip: Boolean = true
        var serializer: EventSerializer = DefaultJsonSerializer()
        var networkClient: NetworkClient = DefaultNetworkClient()
        var retryPolicy: RetryPolicy = ExponentialBackoff()
        var responseClassifier: ResponseClassifier = DefaultResponseClassifier()

        /** Return true to send the event here. Runs on the SDK writer thread after `beforeSend`. */
        var filter: ((AnalyticsEvent) -> Boolean)? = null

        fun header(name: String, value: String) {
            headers[name] = value
        }

        fun build(): Destination {
            require(ID_PATTERN.matches(id)) { "Destination id must match ${ID_PATTERN.pattern}: $id" }
            require(id != DEFAULT_ID) { "'$DEFAULT_ID' is the id of the main endpoint" }
            require(endpoint.startsWith("https://") || endpoint.startsWith("http://")) {
                "endpoint must be an http(s) URL: $endpoint"
            }
            require(batchSize in 1..500) { "batchSize must be in 1..500" }
            return Destination(this)
        }

        /** For the implicit `default` destination built from [AnalyticsConfig]. */
        internal fun buildDefault(): Destination = Destination(this)
    }

    companion object {
        const val DEFAULT_ID = "default"
        private val ID_PATTERN = Regex("[a-z0-9_]{1,32}")
    }
}

fun Destination(id: String, endpoint: String, block: Destination.Builder.() -> Unit = {}): Destination =
    Destination.Builder(id, endpoint).apply(block).build()
