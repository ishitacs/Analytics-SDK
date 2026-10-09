package com.example.analytics.network

import org.json.JSONObject

/**
 * One upload unit.
 *
 * @property batchId unique per request payload (a batch split after a 413/4xx gets derived ids such
 * as `<id>-1`, `<id>-2`); backends can use it for request-level idempotency in addition to
 * per-event `event_id` dedupe. A retry after a failure is a new claim with a new id.
 * @property events each entry is one event envelope as a JSON object string.
 */
class EventBatch(
    val batchId: String,
    val sentAtMillis: Long,
    val events: List<String>,
)

/**
 * Turns a batch into the request body. Implement to match a backend contract
 * (different wrapper key, field renames, NDJSON, protobuf, ...).
 */
interface EventSerializer {
    val contentType: String
    fun serialize(batch: EventBatch): ByteArray
}

/**
 * Default contract:
 * ```
 * { "batch_id": "...", "sent_at": 1728470000000, "events": [ {envelope}, ... ] }
 * ```
 * `sent_at` lets the backend correct device clock skew: `server_received_at - sent_at` is the
 * offset to apply to every `client_ts` in the batch.
 */
class DefaultJsonSerializer : EventSerializer {
    override val contentType: String = "application/json; charset=utf-8"

    override fun serialize(batch: EventBatch): ByteArray {
        // Envelopes are stored as JSON already; splice them in rather than re-parsing each one.
        val builder = StringBuilder(batch.events.sumOf { it.length + 1 } + 96)
        builder.append("{\"batch_id\":").append(JSONObject.quote(batch.batchId))
        builder.append(",\"sent_at\":").append(batch.sentAtMillis)
        builder.append(",\"events\":[")
        batch.events.forEachIndexed { index, event ->
            if (index > 0) builder.append(',')
            builder.append(event)
        }
        builder.append("]}")
        return builder.toString().toByteArray(Charsets.UTF_8)
    }
}
