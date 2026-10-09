package com.example.analytics.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.analytics.AnalyticsConfig
import com.example.analytics.EventPriority
import com.example.analytics.EventType
import com.example.analytics.FakeBackend
import com.example.analytics.FakeClock
import com.example.analytics.SilentLogger
import com.example.analytics.network.HttpResponse
import com.example.analytics.storage.QueueDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Duration.Companion.hours

/** End-to-end through the real writer, SQLite queue and upload loop, with a fake backend. */
@RunWith(RobolectricTestRunner::class)
class AnalyticsClientTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val backend = FakeBackend()
    private val clients = mutableListOf<AnalyticsClient>()

    @After
    fun tearDown() {
        clients.forEach { it.shutdown() }
        QueueDatabase.closeAll()
        context.databaseList().forEach { context.deleteDatabase(it) }
    }

    private fun newClient(block: AnalyticsConfig.Builder.() -> Unit = {}): AnalyticsClient {
        val config = AnalyticsConfig("https://example.test/events") {
            networkClient = backend
            logger = SilentLogger
            backgroundUploads = false
            flushInterval = 1.hours // only explicit flushes in tests
            block()
        }
        return AnalyticsClient(context, config, clock, { ProcessInfo(context.packageName, context.packageName) })
            .also { clients += it }
            .also { it.start() }
    }

    /** Events the test tracked, excluding SDK-emitted session_start / identify events. */
    private val tracked: List<JSONObject>
        get() = backend.deliveredEvents.filter { it.getString("type") !in setOf("system", "identify") }

    private fun track(
        client: AnalyticsClient,
        name: String,
        props: Map<String, Any?> = emptyMap(),
        clientTs: Long = clock.now,
    ) = client.enqueue(Command.Track(name, EventType.CLICK, props, EventPriority.NORMAL, clientTs, clock.elapsed))

    private fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for condition" }
            Thread.sleep(20)
        }
    }

    private fun flushUntilDelivered(client: AnalyticsClient, count: Int) = waitUntil {
        client.requestFlush(FlushReason.MANUAL)
        Thread.sleep(50)
        tracked.size >= count
    }

    @Test
    fun `tracked event is delivered with the full reserved envelope`() {
        val client = newClient()
        client.enqueue(Command.Identify("user-42", emptyMap(), clock.now, clock.elapsed))
        track(client, "buy_button", mapOf("sku" to "A1", "event_id" to "spoofed", "app_version" to "fake"))
        flushUntilDelivered(client, 1)

        val event = tracked.single()
        assertEquals("buy_button", event.getString("name"))
        assertEquals("click", event.getString("type"))
        assertEquals("user-42", event.getString("user_id"))
        assertNotEquals("spoofed", event.getString("event_id"))
        listOf("anonymous_id", "device_id", "seq", "client_ts", "app_version", "sdk_version", "os_version", "context")
            .forEach { assertTrue("missing $it", event.has(it)) }
        val properties = event.getJSONObject("properties")
        assertEquals("A1", properties.getString("sku"))
        assertFalse(properties.has("event_id"))
        assertFalse(properties.has("app_version"))
    }

    @Test
    fun `identity is captured at track time, in call order`() {
        val client = newClient()
        track(client, "before_login")
        client.enqueue(Command.Identify("u1", emptyMap(), clock.now, clock.elapsed))
        track(client, "after_login")
        client.enqueue(Command.Reset(clock.now))
        track(client, "after_logout")
        flushUntilDelivered(client, 3)

        val byName = tracked.associateBy { it.getString("name") }
        assertTrue(byName.getValue("before_login").isNull("user_id"))
        assertEquals("u1", byName.getValue("after_login").getString("user_id"))
        assertTrue(byName.getValue("after_logout").isNull("user_id"))
        assertNotEquals(
            byName.getValue("after_login").getString("anonymous_id"),
            byName.getValue("after_logout").getString("anonymous_id"),
        )
        assertEquals(
            byName.getValue("after_login").getString("device_id"),
            byName.getValue("after_logout").getString("device_id"),
        )
    }

    @Test
    fun `events tracked offline survive a process restart and are delivered later`() {
        backend.responder = FakeBackend.offline()
        val first = newClient()
        repeat(5) { track(first, "offline_$it") }
        first.enqueue(Command.Flush)
        waitUntil { backend.requests.isNotEmpty() }
        first.shutdown()

        backend.responder = { HttpResponse(200) }
        val restarted = newClient()
        flushUntilDelivered(restarted, 5)
        assertEquals((0 until 5).map { "offline_$it" }.toSet(), tracked.map { it.getString("name") }.toSet())
    }

    @Test
    fun `seq is unique and increasing`() {
        val client = newClient()
        repeat(20) { track(client, "e$it") }
        flushUntilDelivered(client, 20)
        val seqs = tracked.sortedBy { it.getString("name").drop(1).toInt() }.map { it.getLong("seq") }
        assertEquals(seqs.sorted().distinct(), seqs)
    }

    @Test
    fun `beforeSend can scrub or drop events`() {
        val client = newClient {
            beforeSend = { event ->
                if (event.name == "secret") null else event.copy(properties = event.properties - "email")
            }
        }
        track(client, "secret")
        track(client, "signup", mapOf("email" to "a@b.c", "plan" to "pro"))
        flushUntilDelivered(client, 1)
        Thread.sleep(200)
        val event = tracked.single()
        assertEquals("signup", event.getString("name"))
        assertEquals(JSONObject().put("plan", "pro").toString(), event.getJSONObject("properties").toString())
    }

    @Test
    fun `opt-out purges the queue and blocks tracking`() {
        backend.responder = FakeBackend.offline()
        val client = newClient()
        repeat(3) { track(client, "queued_$it") }
        client.enqueue(Command.SetOptOut(true))
        track(client, "after_opt_out")
        backend.responder = { HttpResponse(200) }
        client.requestFlush(FlushReason.MANUAL)
        Thread.sleep(500)
        assertTrue(tracked.isEmpty())

        client.enqueue(Command.SetOptOut(false))
        track(client, "opted_back_in")
        flushUntilDelivered(client, 1)
        assertEquals(listOf("opted_back_in"), tracked.map { it.getString("name") })
    }

    @Test
    fun `batch size triggers a flush without an explicit request`() {
        val client = newClient { batchSize = 5 }
        repeat(5) { track(client, "auto_$it") }
        waitUntil { tracked.size == 5 }
    }

    @Test
    fun `worker sweep drains other processes' queues`() {
        val remote = AnalyticsClient(
            context,
            AnalyticsConfig("https://example.test/events") {
                networkClient = FakeBackend(FakeBackend.offline())
                logger = SilentLogger
                backgroundUploads = false
                flushInterval = 1.hours
            },
            clock,
            { ProcessInfo("${context.packageName}:remote", context.packageName) },
        ).also { clients += it; it.start() }
        track(remote, "from_remote")
        waitUntil { context.databaseList().any { it == "analytics_queue_remote.db" } }
        Thread.sleep(200)

        val main = newClient()
        kotlinx.coroutines.runBlocking { main.flushAllQueues() }
        assertEquals(listOf("from_remote"), tracked.map { it.getString("name") })
        assertEquals("${context.packageName}:remote", tracked.single().getJSONObject("context").getString("process"))
    }

    @Test
    fun `every event carries a session id and the first one starts a session`() {
        val client = newClient()
        track(client, "a")
        track(client, "b")
        flushUntilDelivered(client, 2)
        val all = backend.deliveredEvents
        val start = all.single { it.getString("name") == "session_start" }
        assertEquals("first", start.getJSONObject("properties").getString("reason"))
        assertEquals(1, all.map { it.getString("session_id") }.toSet().size)
    }

    @Test
    fun `background inactivity beyond the timeout starts a new session`() {
        val client = newClient()
        track(client, "before")
        client.enqueue(Command.AppBackground(clock.now))
        track(client, "after", clientTs = clock.now + 31 * 60_000)
        flushUntilDelivered(client, 2)
        val byName = tracked.associateBy { it.getString("name") }
        assertNotEquals(byName.getValue("before").getString("session_id"), byName.getValue("after").getString("session_id"))
        val reasons = backend.deliveredEvents.filter { it.getString("name") == "session_start" }
            .map { it.getJSONObject("properties").getString("reason") }
        assertEquals(listOf("first", "timeout"), reasons)
    }

    @Test
    fun `foreground time never expires the session`() {
        val client = newClient()
        client.enqueue(Command.AppForeground(clock.now))
        track(client, "start")
        track(client, "an_hour_later", clientTs = clock.now + 60 * 60_000)
        flushUntilDelivered(client, 2)
        assertEquals(1, tracked.map { it.getString("session_id") }.toSet().size)
    }

    @Test
    fun `identify emits traits, keeps session on login and rotates it on user switch`() {
        val client = newClient()
        track(client, "anonymous")
        client.enqueue(Command.Identify("u1", mapOf("plan" to "pro", "user_id" to "spoof"), clock.now, clock.elapsed))
        track(client, "as_u1")
        client.enqueue(Command.Identify("u2", emptyMap(), clock.now, clock.elapsed))
        track(client, "as_u2")
        flushUntilDelivered(client, 3)

        val identifies = backend.deliveredEvents.filter { it.getString("type") == "identify" }
        assertEquals(listOf("u1", "u2"), identifies.map { it.getString("user_id") })
        val traits = identifies.first().getJSONObject("properties")
        assertEquals("pro", traits.getString("plan"))
        assertFalse(traits.has("user_id"))

        val byName = tracked.associateBy { it.getString("name") }
        assertEquals(byName.getValue("anonymous").getString("session_id"), byName.getValue("as_u1").getString("session_id"))
        assertNotEquals(byName.getValue("as_u1").getString("session_id"), byName.getValue("as_u2").getString("session_id"))
    }

    // ------------------------------------------------------------ destinations

    @Test
    fun `secondary destination gets filtered events and is isolated from the default's failures`() {
        val warehouse = FakeBackend()
        backend.responder = { HttpResponse(503) }
        val client = newClient {
            destination("warehouse", "https://warehouse.test/collect") {
                networkClient = warehouse
                filter = { it.type == EventType.CLICK }
            }
        }
        track(client, "buy_click")
        client.enqueue(Command.Track("page_scrolled", EventType.CUSTOM, emptyMap(), EventPriority.NORMAL, clock.now, clock.elapsed))
        waitUntil {
            client.requestFlush(FlushReason.MANUAL)
            Thread.sleep(50)
            warehouse.deliveredEvents.isNotEmpty()
        }
        assertEquals(listOf("buy_click"), warehouse.deliveredEvents.map { it.getString("name") })
        assertTrue(backend.deliveredEvents.isEmpty())

        // Default recovers and delivers its own copy of everything; the warehouse isn't re-sent anything.
        backend.responder = { HttpResponse(200) }
        flushUntilDelivered(client, 2)
        assertEquals(setOf("buy_click", "page_scrolled"), tracked.map { it.getString("name") }.toSet())
        assertEquals(1, warehouse.deliveredEvents.size)
        assertEquals(
            warehouse.deliveredEvents.single().getString("event_id"),
            tracked.single { it.getString("name") == "buy_click" }.getString("event_id"),
        )
    }

    // ------------------------------------------------------------ remote config

    private fun remoteClient(json: () -> String, etag: String? = null): AnalyticsClient {
        backend.configResponder = {
            HttpResponse(200, etag?.let { mapOf("ETag" to listOf(it)) } ?: emptyMap(), json().toByteArray())
        }
        val client = newClient { remoteConfigUrl = "https://example.test/config" }
        waitUntil { backend.configRequests.isNotEmpty() }
        Thread.sleep(100)
        return client
    }

    @Test
    fun `remote kill switch stops collecting events`() {
        val client = remoteClient({ """{"tracking_enabled":false}""" })
        track(client, "ignored")
        client.requestFlush(FlushReason.MANUAL)
        Thread.sleep(400)
        assertTrue(backend.deliveredEvents.isEmpty())
    }

    @Test
    fun `remote blocked events and sampling rates are applied`() {
        val client = remoteClient({ """{"blocked_events":["debug_*"],"event_sample_rates":{"never":0}}""" })
        track(client, "debug_fps")
        track(client, "never")
        track(client, "kept")
        flushUntilDelivered(client, 1)
        Thread.sleep(200)
        assertEquals(listOf("kept"), tracked.map { it.getString("name") })
        assertEquals(1.0, tracked.single().getJSONObject("context").getDouble("sample_rate"), 0.0)
    }

    @Test
    fun `device sampled out sends nothing, not even sessions`() {
        val client = remoteClient({ """{"sample_rate":0}""" })
        track(client, "a")
        client.requestFlush(FlushReason.MANUAL)
        Thread.sleep(400)
        assertTrue(backend.deliveredEvents.isEmpty())
    }

    @Test
    fun `uploads kill switch keeps events on device until lifted, using etag revalidation`() {
        var json = """{"uploads_enabled":false}"""
        val client = remoteClient({ json }, etag = "\"v1\"")
        track(client, "held")
        client.requestFlush(FlushReason.MANUAL)
        Thread.sleep(400)
        assertTrue(backend.deliveredEvents.isEmpty())

        json = """{"uploads_enabled":true}"""
        client.refreshRemoteConfig()
        flushUntilDelivered(client, 1)
        assertEquals(listOf("held"), tracked.map { it.getString("name") })
        assertEquals("\"v1\"", backend.configRequests.last().headers["If-None-Match"])
    }

    @Test
    fun `remote batch size resizes the default destination's batches`() {
        val client = remoteClient({ """{"batch_size":2}""" })
        repeat(5) { track(client, "e$it") }
        flushUntilDelivered(client, 5)
        assertTrue(backend.bodies.all { it.getJSONArray("events").length() <= 2 })
    }

    @Test
    fun `remote config failure keeps defaults`() {
        backend.configResponder = { HttpResponse(500) }
        val client = newClient { remoteConfigUrl = "https://example.test/config" }
        waitUntil { backend.configRequests.isNotEmpty() }
        track(client, "still_tracked")
        flushUntilDelivered(client, 1)
    }
}
