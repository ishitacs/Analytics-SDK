package com.example.analytics.network

import java.io.IOException

class HttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

class HttpResponse(
    val code: Int,
    headers: Map<String, List<String>> = emptyMap(),
    /** Response body; only needed for remote config. Clients may leave it null for event uploads. */
    val body: ByteArray? = null,
) {
    private val headers: Map<String, List<String>> =
        headers.mapKeys { it.key.lowercase() }

    /** Case-insensitive; returns the first value. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
}

/**
 * Transport used to deliver batches. Called on a background thread; may block.
 *
 * Implement this to reuse the app's own HTTP stack (OkHttp interceptors, certificate pinning,
 * proxies). Throw [IOException] for transport failures (no connectivity, timeouts, DNS) — those
 * are retried without counting against [com.example.analytics.AnalyticsConfig.maxAttempts].
 */
fun interface NetworkClient {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}
