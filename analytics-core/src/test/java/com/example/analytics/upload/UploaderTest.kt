package com.example.analytics.upload

import com.example.analytics.AnalyticsConfig
import com.example.analytics.DropReason
import com.example.analytics.FakeBackend
import com.example.analytics.FakeClock
import com.example.analytics.SilentLogger
import com.example.analytics.network.HttpResponse
import com.example.analytics.storage.InMemoryEventStore
import com.example.analytics.storage.PendingEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.days

@RunWith(RobolectricTestRunner::class)
class UploaderTest {

    private val clock = FakeClock()
    private val backend = FakeBackend()
    private val store = InMemoryEventStore()
    private val dropped = mutableMapOf<DropReason, Int>()

    private fun uploader(batchSize: Int = 10, block: AnalyticsConfig.Builder.() -> Unit = {}) = Uploader(
        config = AnalyticsConfig("https://example.test/events") {
            this.batchSize = batchSize
            networkClient = backend
            logger = SilentLogger
            header("X-App-Key", "key")
            block()
        },
        clock = clock,
        onDropped = { count, reason -> dropped.merge(reason, count, Int::plus) },
    )

    private fun enqueue(count: Int, createdAt: Long = clock.now) =
        store.insert((1..count).map { PendingEvent("e$it", JSONObject().put("event_id", "e$it").toString(), 0, createdAt) })

    private fun eventIds(body: JSONObject) =
        body.getJSONArray("events").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("event_id") } }

    @Test
    fun `uploads everything in batches and empties the queue`() {
        enqueue(25)
        val result = uploader(batchSize = 10).drain(store)
        assertEquals(DrainResult.Done(25), result)
        assertEquals(listOf(10, 10, 5), backend.bodies.map { it.getJSONArray("events").length() })
        assertEquals(0, store.count())
    }

    @Test
    fun `request carries headers, gzip and batch metadata`() {
        var token = "initial"
        enqueue(1)
        uploader { headerProvider = { mapOf("Authorization" to "Bearer $token") } }.drain(store)
        val request = backend.requests.single()
        assertEquals("key", request.headers["X-App-Key"])
        assertEquals("Bearer initial", request.headers["Authorization"])
        assertEquals("gzip", request.headers["Content-Encoding"])
        assertEquals(clock.now, backend.bodies.single().getLong("sent_at"))
        assertTrue(backend.bodies.single().getString("batch_id").isNotEmpty())
        token = "rotated"
        store.insert(listOf(PendingEvent("x", "{\"event_id\":\"x\"}", 0, clock.now)))
        uploader { headerProvider = { mapOf("Authorization" to "Bearer $token") } }.drain(store)
        assertEquals("Bearer rotated", backend.requests.last().headers["Authorization"])
    }

    @Test
    fun `network error keeps events without counting an attempt`() {
        enqueue(3)
        backend.responder = FakeBackend.offline()
        val result = uploader().drain(store)
        assertEquals(DrainResult.Retry(networkError = true, retryAfterMillis = null), result)
        assertEquals(3, store.count())
        assertEquals(0, store.deleteExhausted(1))
    }

    @Test
    fun `server error keeps events, counts attempt and honours retry-after`() {
        enqueue(3)
        backend.responder = { HttpResponse(503, mapOf("Retry-After" to listOf("120"))) }
        val result = uploader().drain(store)
        assertEquals(DrainResult.Retry(networkError = false, retryAfterMillis = 120_000), result)
        assertEquals(3, store.count())
        assertEquals(3, store.deleteExhausted(1))
    }

    @Test
    fun `413 splits the batch until it fits`() {
        enqueue(8)
        backend.responder = { body -> HttpResponse(if (body.getJSONArray("events").length() > 2) 413 else 200) }
        val result = uploader().drain(store)
        assertEquals(DrainResult.Done(8), result)
        assertEquals((1..8).map { "e$it" }, backend.deliveredEvents.map { it.getString("event_id") })
    }

    @Test
    fun `every request of a split batch has its own batch id`() {
        enqueue(8)
        backend.responder = { body -> HttpResponse(if (body.getJSONArray("events").length() > 2) 413 else 200) }
        uploader().drain(store)
        val ids = backend.bodies.map { it.getString("batch_id") }
        assertEquals(7, ids.size) // 8 -> 4+4 -> 2+2+2+2
        assertEquals(ids.toSet().size, ids.size)
    }

    @Test
    fun `400 bisects and drops only the poison event`() {
        enqueue(8)
        backend.responder = { body -> HttpResponse(if ("e5" in eventIds(body)) 400 else 200) }
        val result = uploader().drain(store)
        assertEquals(DrainResult.Done(7), result)
        assertEquals((1..8).map { "e$it" } - "e5", backend.deliveredEvents.map { it.getString("event_id") })
        assertEquals(1, dropped[DropReason.REJECTED])
        assertEquals(0, store.count())
    }

    @Test
    fun `failure mid-split releases the unsent half`() {
        enqueue(4)
        var calls = 0
        backend.responder = { calls++; if (calls == 1) HttpResponse(413) else HttpResponse(500) }
        val result = uploader().drain(store)
        assertTrue(result is DrainResult.Retry)
        assertEquals(4, store.count())
        assertEquals(4, store.claim(10, clock.now, 1)!!.events.size)
    }

    @Test
    fun `expired events are dropped before upload`() {
        enqueue(2, createdAt = clock.now - 8.days.inWholeMilliseconds)
        val result = uploader().drain(store)
        assertEquals(DrainResult.Done(0), result)
        assertEquals(2, dropped[DropReason.EXPIRED])
        assertTrue(backend.requests.isEmpty())
    }
}
