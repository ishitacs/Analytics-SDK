package com.example.analytics.upload

import com.example.analytics.AnalyticsConfig
import com.example.analytics.AnalyticsLogger
import com.example.analytics.Destination
import com.example.analytics.DropReason
import com.example.analytics.internal.Clock
import com.example.analytics.network.EventBatch
import com.example.analytics.network.HttpRequest
import com.example.analytics.network.HttpResponse
import com.example.analytics.network.ResponseAction
import com.example.analytics.storage.EventStore
import com.example.analytics.storage.StoredEvent
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPOutputStream

internal sealed interface DrainResult {
    /** Queue empty (or nothing claimable). */
    data class Done(val uploaded: Int) : DrainResult

    /** Stopped on a transient failure; remaining rows stay queued. */
    data class Retry(val networkError: Boolean, val retryAfterMillis: Long?) : DrainResult
}

/**
 * Drains an [EventStore] batch by batch. Blocking; call from a background thread.
 *
 * Failure handling per batch:
 * - IOException: release without counting an attempt (offline isn't the event's fault).
 * - RETRY (5xx/429/...): release and count an attempt; honour `Retry-After`.
 * - PAYLOAD_TOO_LARGE: split in half and resend each half; a single oversized event is dropped.
 * - REJECT: bisect to isolate the bad event(s); drop only those.
 */
internal class Uploader(
    private val destination: Destination,
    private val eventTtlMillis: Long,
    private val maxAttempts: Int,
    private val logger: AnalyticsLogger,
    private val clock: Clock,
    private val onUploaded: (count: Int) -> Unit = {},
    private val onDropped: (count: Int, reason: DropReason) -> Unit = { _, _ -> },
    private val onFailed: (count: Int, error: String) -> Unit = { _, _ -> },
) {
    /** Uploader for the config's `default` destination. */
    constructor(
        config: AnalyticsConfig,
        clock: Clock,
        onUploaded: (count: Int) -> Unit = {},
        onDropped: (count: Int, reason: DropReason) -> Unit = { _, _ -> },
        onFailed: (count: Int, error: String) -> Unit = { _, _ -> },
    ) : this(
        config.allDestinations.first(), config.eventTtl.inWholeMilliseconds, config.maxAttempts, config.logger,
        clock, onUploaded, onDropped, onFailed,
    )

    val destinationId: String get() = destination.id

    /** @param batchSize overrides the destination's (remote config can change it at runtime). */
    fun drain(store: EventStore, batchSize: Int = destination.batchSize): DrainResult {
        val now = clock.currentTimeMillis()
        store.deleteExpired(now - eventTtlMillis).let { if (it > 0) onDropped(it, DropReason.EXPIRED) }
        store.deleteExhausted(maxAttempts).let { if (it > 0) onDropped(it, DropReason.MAX_ATTEMPTS) }

        var uploaded = 0
        while (true) {
            val batch = store.claim(batchSize, clock.currentTimeMillis(), LEASE_MILLIS)
                ?: return DrainResult.Done(uploaded)
            when (val outcome = send(store, batch.token, batch.events)) {
                is Outcome.Sent -> uploaded += outcome.delivered
                is Outcome.Failed -> return DrainResult.Retry(outcome.networkError, outcome.retryAfterMillis)
            }
        }
    }

    private sealed interface Outcome {
        class Sent(val delivered: Int) : Outcome
        class Failed(val networkError: Boolean, val retryAfterMillis: Long?) : Outcome
    }

    /**
     * @param batchId the claim token for a whole claimed batch; split halves get derived ids, since
     * backends may dedupe on `batch_id` and must never see one id for two different payloads.
     */
    private fun send(store: EventStore, token: String, events: List<StoredEvent>, batchId: String = token): Outcome {
        val rowIds = events.map { it.rowId }
        val response = try {
            destination.networkClient.execute(buildRequest(batchId, events))
        } catch (e: IOException) {
            logger.debug("Upload of ${events.size} events failed: ${e.message}")
            store.release(token, rowIds, countAttempt = false)
            onFailed(events.size, e.toString())
            return Outcome.Failed(networkError = true, retryAfterMillis = null)
        } catch (e: Exception) {
            // A misbehaving custom NetworkClient/serializer/headerProvider must not kill the pipeline.
            logger.error("Upload failed unexpectedly", e)
            store.release(token, rowIds, countAttempt = true)
            onFailed(events.size, e.toString())
            return Outcome.Failed(networkError = false, retryAfterMillis = null)
        }

        return when (destination.responseClassifier.classify(response)) {
            ResponseAction.SUCCESS -> {
                store.ack(token, rowIds)
                onUploaded(events.size)
                Outcome.Sent(events.size)
            }
            ResponseAction.RETRY -> {
                store.release(token, rowIds, countAttempt = true)
                onFailed(events.size, "HTTP ${response.code}")
                Outcome.Failed(networkError = false, retryAfterMillis = parseRetryAfter(response))
            }
            ResponseAction.PAYLOAD_TOO_LARGE -> splitOrDrop(store, token, batchId, events, "HTTP 413")
            ResponseAction.REJECT -> splitOrDrop(store, token, batchId, events, "HTTP ${response.code}")
        }
    }

    private fun splitOrDrop(
        store: EventStore,
        token: String,
        batchId: String,
        events: List<StoredEvent>,
        reason: String,
    ): Outcome {
        if (events.size == 1) {
            logger.warn("Backend rejected event ${events[0].eventId} ($reason); dropping it")
            store.ack(token, listOf(events[0].rowId))
            onDropped(1, DropReason.REJECTED)
            return Outcome.Sent(0)
        }
        val mid = events.size / 2
        val first = events.subList(0, mid)
        val second = events.subList(mid, events.size)
        val firstOutcome = send(store, token, first, "$batchId-1")
        if (firstOutcome is Outcome.Failed) {
            store.release(token, second.map { it.rowId }, countAttempt = false)
            return firstOutcome
        }
        return when (val secondOutcome = send(store, token, second, "$batchId-2")) {
            is Outcome.Sent -> Outcome.Sent((firstOutcome as Outcome.Sent).delivered + secondOutcome.delivered)
            is Outcome.Failed -> secondOutcome
        }
    }

    private fun buildRequest(batchId: String, events: List<StoredEvent>): HttpRequest {
        val batch = EventBatch(batchId, clock.currentTimeMillis(), events.map { it.payload })
        var body = destination.serializer.serialize(batch)
        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = destination.serializer.contentType
        headers.putAll(destination.headers)
        destination.headerProvider?.invoke()?.let { headers.putAll(it) }
        if (destination.gzip) {
            body = gzip(body)
            headers["Content-Encoding"] = "gzip"
        }
        return HttpRequest(destination.endpoint, destination.httpMethod, headers, body)
    }

    private fun parseRetryAfter(response: HttpResponse): Long? {
        val value = response.header("Retry-After")?.trim() ?: return null
        value.toLongOrNull()?.let { return it * 1000 }
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            val date = format.parse(value) ?: return null
            (date.time - clock.currentTimeMillis()).coerceAtLeast(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    companion object {
        /** Rows claimed longer ago than this are assumed orphaned (process killed mid-upload). */
        const val LEASE_MILLIS = 10 * 60 * 1000L
    }
}
