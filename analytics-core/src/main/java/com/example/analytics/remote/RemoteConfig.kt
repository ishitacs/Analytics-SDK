package com.example.analytics.remote

import org.json.JSONObject

/**
 * Server-controlled settings. Wire format (every field optional; unknown fields ignored):
 * ```
 * {
 *   "tracking_enabled": true,          // false: stop collecting new events (kill switch)
 *   "uploads_enabled": true,           // false: keep events on device, stop sending (ingestion outage)
 *   "sample_rate": 1.0,                // fraction of devices that track at all
 *   "event_sample_rates": {"scroll": 0.1},
 *   "blocked_events": ["debug_*", "legacy_click"],
 *   "batch_size": 50,                  // default destination
 *   "flush_interval_seconds": 30,
 *   "ttl_seconds": 3600                // how long until the next fetch
 * }
 * ```
 * Out-of-range values are clamped, so a typo on the server can't take every client down.
 */
internal class RemoteConfig(
    val trackingEnabled: Boolean = true,
    val uploadsEnabled: Boolean = true,
    val sampleRate: Double = 1.0,
    val eventSampleRates: Map<String, Double> = emptyMap(),
    blockedEvents: List<String> = emptyList(),
    val batchSize: Int? = null,
    val flushIntervalMillis: Long? = null,
    val ttlMillis: Long? = null,
) {
    private val blockedExact = blockedEvents.filterNot { it.endsWith("*") }.toHashSet()
    private val blockedPrefixes = blockedEvents.filter { it.endsWith("*") }.map { it.dropLast(1) }

    fun isBlocked(name: String): Boolean = name in blockedExact || blockedPrefixes.any { name.startsWith(it) }

    /** Rate that applies to [name]: its own override, else the global rate. */
    fun sampleRateFor(name: String): Double = eventSampleRates[name] ?: sampleRate

    companion object {
        val DEFAULT = RemoteConfig()

        /** @throws org.json.JSONException on malformed JSON (callers keep the previous config). */
        fun parse(json: String): RemoteConfig {
            val obj = JSONObject(json)
            val rates = obj.optJSONObject("event_sample_rates")?.let { rates ->
                rates.keys().asSequence().associateWith { rates.optDouble(it, 1.0).clampRate() }
            } ?: emptyMap()
            val blocked = obj.optJSONArray("blocked_events")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList()
            return RemoteConfig(
                trackingEnabled = obj.optBoolean("tracking_enabled", true),
                uploadsEnabled = obj.optBoolean("uploads_enabled", true),
                sampleRate = obj.optDouble("sample_rate", 1.0).clampRate(),
                eventSampleRates = rates,
                blockedEvents = blocked,
                batchSize = obj.optIntOrNull("batch_size")?.coerceIn(1, 500),
                flushIntervalMillis = obj.optIntOrNull("flush_interval_seconds")?.coerceIn(5, 3_600)?.times(1_000L),
                ttlMillis = obj.optIntOrNull("ttl_seconds")?.coerceIn(60, 86_400)?.times(1_000L),
            )
        }

        private fun Double.clampRate() = if (isNaN()) 1.0 else coerceIn(0.0, 1.0)

        /** Non-numbers (e.g. a typo'd string) are ignored rather than coerced to 0. */
        private fun JSONObject.optIntOrNull(key: String): Int? = (opt(key) as? Number)?.toInt()
    }
}

/**
 * Per-device sampling: each install falls into one fixed bucket in [0, 1), so a sampled-in device
 * keeps its whole journey (no holes from per-event coin flips), and lowering a rate only ever
 * removes devices — the remaining sample is stable. Backends can reproduce the bucket:
 * `(java String.hashCode(device_id) & 0x7fffffff) % 10000 / 10000.0`.
 */
internal object Sampling {
    fun bucket(deviceId: String): Double = (deviceId.hashCode() and 0x7fffffff) % 10_000 / 10_000.0

    fun isSampledIn(deviceId: String, rate: Double): Boolean = rate >= 1.0 || bucket(deviceId) < rate
}
