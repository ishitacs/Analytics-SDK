package com.example.analytics.remote

import com.example.analytics.AnalyticsLogger
import com.example.analytics.Destination
import com.example.analytics.internal.Clock
import com.example.analytics.network.HttpRequest
import com.example.analytics.storage.MetaStore
import java.io.IOException
import kotlin.random.Random

/**
 * Fetches, caches and serves [RemoteConfig].
 *
 * - Cached in the cross-process [MetaStore]: every process applies the same config, the last good
 *   config survives restarts and offline launches, and only the main process fetches.
 * - Conditional GET with `If-None-Match`: an unchanged config costs a 304 and no body.
 * - Refresh interval is jittered ±10% so a fleet of devices doesn't fetch in lockstep.
 * - Any failure (network, 5xx, malformed JSON) keeps the previous config and retries later.
 */
internal class RemoteConfigManager(
    private val url: String?,
    private val destination: Destination,
    private val defaultRefreshMillis: Long,
    private val meta: () -> MetaStore,
    private val clock: Clock,
    private val logger: AnalyticsLogger,
    private val random: Random = Random.Default,
) {
    @Volatile
    private var cached: RemoteConfig = RemoteConfig.DEFAULT

    @Volatile
    private var loadedAt: Long? = null

    val isEnabled: Boolean get() = url != null

    /** Current config, re-read from the shared store every few seconds to pick up other processes' fetches. */
    fun current(): RemoteConfig {
        if (url == null) return RemoteConfig.DEFAULT
        val now = clock.elapsedRealtime()
        loadedAt?.let { if (now - it < RELOAD_MILLIS) return cached }
        loadedAt = now
        try {
            meta().get(KEY_JSON)?.let { cached = RemoteConfig.parse(it) }
        } catch (e: Exception) {
            logger.warn("Cached remote config unreadable; keeping previous", e)
        }
        return cached
    }

    /** Fetches if the cached config is due for refresh (or [force]). Blocking; call off the main thread. */
    fun refreshIfDue(force: Boolean = false) {
        val url = url ?: return
        val store = meta()
        val now = clock.currentTimeMillis()
        val nextFetchAt = store.get(KEY_NEXT_FETCH_AT)?.toLongOrNull() ?: 0L
        if (!force && now < nextFetchAt) return

        // Claim the slot before fetching: a failure must not cause a retry storm.
        store.put(KEY_NEXT_FETCH_AT, (now + RETRY_AFTER_FAILURE_MILLIS).toString())
        try {
            val headers = LinkedHashMap<String, String>()
            headers["Accept"] = "application/json"
            headers.putAll(destination.headers)
            destination.headerProvider?.invoke()?.let { headers.putAll(it) }
            store.get(KEY_ETAG)?.let { headers["If-None-Match"] = it }

            val response = destination.networkClient.execute(HttpRequest(url, "GET", headers, ByteArray(0)))
            when (response.code) {
                304 -> scheduleNext(store, now, current().ttlMillis)
                in 200..299 -> {
                    val json = response.body?.toString(Charsets.UTF_8)
                        ?: throw IOException("Remote config response had no body")
                    val parsed = RemoteConfig.parse(json) // throws on malformed JSON: keep previous
                    store.put(KEY_JSON, json)
                    store.put(KEY_ETAG, response.header("ETag"))
                    cached = parsed
                    loadedAt = clock.elapsedRealtime()
                    scheduleNext(store, now, parsed.ttlMillis)
                    logger.debug("Remote config updated: tracking=${parsed.trackingEnabled} uploads=${parsed.uploadsEnabled} sample=${parsed.sampleRate}")
                }
                else -> logger.warn("Remote config fetch failed: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            logger.warn("Remote config fetch failed; keeping previous config", e)
        }
    }

    private fun scheduleNext(store: MetaStore, now: Long, ttlMillis: Long?) {
        val base = ttlMillis ?: defaultRefreshMillis
        val jitter = (base * 0.1 * (random.nextDouble() * 2 - 1)).toLong()
        store.put(KEY_NEXT_FETCH_AT, (now + base + jitter).toString())
    }

    private companion object {
        const val KEY_JSON = "remote_config_json"
        const val KEY_ETAG = "remote_config_etag"
        const val KEY_NEXT_FETCH_AT = "remote_config_next_fetch_at"
        const val RELOAD_MILLIS = 5_000L
        const val RETRY_AFTER_FAILURE_MILLIS = 5 * 60_000L
    }
}
