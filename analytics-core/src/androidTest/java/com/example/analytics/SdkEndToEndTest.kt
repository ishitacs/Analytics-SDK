package com.example.analytics

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.analytics.internal.AppLifecycleTracker
import com.example.analytics.storage.QueueDatabase
import com.example.analytics.upload.AnalyticsUploadWorker
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.hours

/**
 * On-device end-to-end: public [Analytics] API → real SQLite queue → [com.example.analytics.network.DefaultNetworkClient]
 * (HttpURLConnection) → a real HTTP server on localhost.
 */
@RunWith(AndroidJUnit4::class)
class SdkEndToEndTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var server: MockWebServer

    /** Decides the status for each upload request; default 200. */
    @Volatile private var responder: (JSONObject) -> MockResponse = { MockResponse().setResponseCode(200) }
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val bodies = CopyOnWriteArrayList<Pair<JSONObject, Int>>()
    private val drops = CopyOnWriteArrayList<Pair<Int, DropReason>>()

    private val delivered: List<JSONObject>
        get() = bodies.filter { it.second in 200..299 }.flatMap { (body, _) ->
            val events = body.getJSONArray("events")
            (0 until events.length()).map { events.getJSONObject(it) }
        }

    /** Excludes SDK-emitted session_start / identify / sdk_events_dropped. */
    private val deliveredUserEvents get() = delivered.filter { it.getString("type") !in setOf("system", "identify") }

    @Before
    fun setUp() {
        wipe()
        server = startServer()
    }

    @After
    fun tearDown() {
        server.shutdown()
        wipe()
    }

    private fun wipe() {
        Analytics.resetForTests()
        QueueDatabase.closeAll()
        context.databaseList().forEach { context.deleteDatabase(it) }
    }

    private fun startServer(port: Int = 0) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse().setResponseCode(404)
                requests += request
                val raw = request.body.readByteArray()
                val bytes = if (request.getHeader("Content-Encoding") == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
                val body = JSONObject(String(bytes, Charsets.UTF_8))
                val response = responder(body)
                bodies += body to (response.status.split(" ")[1].toInt())
                return response
            }
        }
        start(port)
    }

    private fun init(block: AnalyticsConfig.Builder.() -> Unit = {}) {
        Analytics.init(
            context,
            AnalyticsConfig(server.url("/v1/events").toString()) {
                header("X-App-Key", "test-key")
                headerProvider = { mapOf("Authorization" to "Bearer t-${requests.size}") }
                backgroundUploads = false
                flushInterval = 1.hours // only explicit flushes
                listener = object : AnalyticsListener {
                    override fun onEventsDropped(count: Int, reason: DropReason) {
                        drops += count to reason
                    }
                }
                block()
            },
        )
    }

    private fun waitUntil(timeoutMillis: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $message" }
            Thread.sleep(50)
        }
    }

    private fun flushUntil(count: Int, timeoutMillis: Long = 10_000) = waitUntil(timeoutMillis, "$count events, got ${deliveredUserEvents.size}") {
        Analytics.flush()
        Thread.sleep(150)
        deliveredUserEvents.size >= count
    }

    // ------------------------------------------------------------------ init, tracking, payload

    @Test
    fun preInitCallsAreBufferedAndTheFullPayloadIsSentOverHttp() {
        Analytics.track("before_init", mapOf("n" to 1))
        init()
        Analytics.setSuperProperties(mapOf("build" to "e2e"))
        Analytics.track(
            "purchase",
            mapOf(
                "price" to 9.99, "qty" to 2, "ok" to true, "none" to null, "tags" to listOf("a", "b"),
                "nested" to mapOf("k" to mapOf("x" to 1)), "user_id" to "spoof", "nan" to Double.NaN,
                "long" to "x".repeat(5_000), "emoji" to "héllo ✓ 🎉",
            ),
            EventPriority.HIGH,
        )
        Analytics.trackClick("buy_button")
        Analytics.trackImpression("product_impression", itemKey = "sku-1")
        Analytics.screen("Home")
        flushUntil(5)

        val request = requests.first()
        assertEquals("POST", request.method)
        assertEquals("/v1/events", request.path)
        assertEquals("gzip", request.getHeader("Content-Encoding"))
        assertEquals("test-key", request.getHeader("X-App-Key"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Bearer t-"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = bodies.first().first
        assertTrue(body.getString("batch_id").isNotEmpty())
        assertTrue(body.getLong("sent_at") > 0)

        val events = deliveredUserEvents
        assertEquals(listOf("before_init", "purchase", "buy_button", "product_impression", "Home"), events.map { it.getString("name") })
        assertEquals(listOf("custom", "custom", "click", "impression", "screen"), events.map { it.getString("type") })

        val purchase = events[1]
        for (key in listOf("event_id", "anonymous_id", "device_id", "session_id", "seq", "client_ts", "elapsed_realtime", "app_version", "sdk_version", "os_version", "schema_version", "context")) {
            assertTrue("missing $key", purchase.has(key))
        }
        assertTrue(purchase.isNull("user_id"))
        val p = purchase.getJSONObject("properties")
        assertEquals(9.99, p.getDouble("price"), 0.0)
        assertEquals(2, p.getInt("qty"))
        assertTrue(p.getBoolean("ok"))
        assertTrue(p.isNull("none"))
        assertEquals(2, p.getJSONArray("tags").length())
        assertEquals(1, p.getJSONObject("nested").getJSONObject("k").getInt("x"))
        assertFalse("reserved key must be dropped", p.has("user_id"))
        assertFalse("NaN must be dropped", p.has("nan"))
        assertEquals(1024, p.getString("long").length)
        assertEquals("héllo ✓ 🎉", p.getString("emoji"))
        assertEquals("e2e", p.getString("build"))
        assertEquals("sku-1", events[3].getJSONObject("properties").getString("item_key"))

        // Session started before the first event; everything shares it.
        val all = delivered
        assertEquals("session_start", all.first().getString("name"))
        assertEquals(1, all.map { it.getString("session_id") }.toSet().size)
        // Ids are unique, seq strictly increasing in tracking order.
        assertEquals(all.size, all.map { it.getString("event_id") }.toSet().size)
        val seqs = all.map { it.getLong("seq") }
        assertEquals(seqs.sorted(), seqs)
        assertEquals(seqs.size, seqs.toSet().size)
    }

    @Test
    fun invalidInputNeverThrowsAndIsDroppedOrSanitized() {
        init()
        Analytics.track("")
        Analytics.track("   ")
        Analytics.track("n".repeat(500))
        Analytics.track("huge", mapOf("blob" to List(100) { "y".repeat(1024) })) // > 32KB envelope
        Analytics.track("weird", mapOf("obj" to Any(), "" to 1, "deep" to mapOf("a" to mapOf("b" to mapOf("c" to mapOf("d" to mapOf("e" to 1)))))))
        Analytics.track("ok")
        flushUntil(2)
        assertEquals(listOf("weird", "ok"), deliveredUserEvents.map { it.getString("name") })
        val weird = deliveredUserEvents[0].getJSONObject("properties")
        assertTrue(weird.getString("obj").startsWith("java.lang.Object@"))
        assertFalse(weird.has(""))
        assertEquals(4, drops.filter { it.second == DropReason.INVALID }.sumOf { it.first })
    }

    @Test
    fun beforeSendCanScrubAndDrop_andAThrowingHookOrListenerDoesNotBreakThePipeline() {
        init {
            beforeSend = { e ->
                when (e.name) {
                    "secret" -> null
                    "boom" -> error("hook bug")
                    else -> e.copy(properties = e.properties - "email")
                }
            }
            listener = object : AnalyticsListener {
                override fun onEventsQueued(queueSize: Int) = throw IllegalStateException("listener bug")
            }
        }
        Analytics.track("secret")
        Analytics.track("boom")
        Analytics.track("signup", mapOf("email" to "a@b.c", "plan" to "pro"))
        flushUntil(2)
        assertEquals(listOf("boom", "signup"), deliveredUserEvents.map { it.getString("name") })
        val props = deliveredUserEvents[1].getJSONObject("properties")
        assertFalse(props.has("email"))
        assertEquals("pro", props.getString("plan"))
    }

    // ------------------------------------------------------------------ identity & sessions

    @Test
    fun identifyAndResetAreAppliedInCallOrder() {
        init()
        Analytics.track("anon")
        Analytics.identify("u1", mapOf("plan" to "free"))
        Analytics.track("as_u1")
        Analytics.identify("u2")
        Analytics.track("as_u2")
        Analytics.reset()
        Analytics.track("after_reset")
        flushUntil(4)

        val e = deliveredUserEvents.associateBy { it.getString("name") }
        assertTrue(e.getValue("anon").isNull("user_id"))
        assertEquals("u1", e.getValue("as_u1").getString("user_id"))
        assertEquals("u2", e.getValue("as_u2").getString("user_id"))
        assertTrue(e.getValue("after_reset").isNull("user_id"))

        // Anonymous -> u1 keeps the session; u1 -> u2 and reset rotate it.
        val session = { name: String -> e.getValue(name).getString("session_id") }
        assertEquals(session("anon"), session("as_u1"))
        assertNotEquals(session("as_u1"), session("as_u2"))
        assertNotEquals(session("as_u2"), session("after_reset"))

        // device id is stable; anonymous id rotates only on reset.
        assertEquals(1, e.values.map { it.getString("device_id") }.toSet().size)
        assertEquals(e.getValue("anon").getString("anonymous_id"), e.getValue("as_u2").getString("anonymous_id"))
        assertNotEquals(e.getValue("as_u2").getString("anonymous_id"), e.getValue("after_reset").getString("anonymous_id"))

        val identifies = delivered.filter { it.getString("type") == "identify" }
        assertEquals(listOf("u1", "u2"), identifies.map { it.getString("user_id") })
        assertEquals("free", identifies[0].getJSONObject("properties").getString("plan"))
        val reasons = delivered.filter { it.getString("name") == "session_start" }.map { it.getJSONObject("properties").getString("reason") }
        assertEquals(listOf("first", "identity_change", "reset"), reasons)
    }

    @Test
    fun identityAndSessionSurviveAProcessRestart() {
        init()
        Analytics.identify("u1")
        Analytics.track("first_launch")
        flushUntil(1)
        val before = deliveredUserEvents.single()

        // Simulated restart: drop the in-memory client and reopen files from disk.
        Analytics.resetForTests()
        QueueDatabase.closeAll()
        init()
        Analytics.track("second_launch")
        flushUntil(2)
        val after = deliveredUserEvents.last()
        assertEquals("u1", after.getString("user_id"))
        assertEquals(before.getString("device_id"), after.getString("device_id"))
        assertEquals(before.getString("anonymous_id"), after.getString("anonymous_id"))
        assertEquals("cold start inside the timeout keeps the session", before.getString("session_id"), after.getString("session_id"))
        assertTrue(after.getLong("seq") > before.getLong("seq"))
    }

    // ------------------------------------------------------------------ delivery & failures

    @Test
    fun eventsTrackedWhileServerIsUnreachableAreDeliveredAfterRestart() {
        val port = server.port
        server.shutdown()
        init()
        repeat(5) { Analytics.track("offline_$it") }
        Analytics.flush()
        Thread.sleep(1_000)
        assertTrue(delivered.isEmpty())

        Analytics.resetForTests()
        QueueDatabase.closeAll()
        server = startServer(port)
        init()
        flushUntil(5)
        assertEquals((0 until 5).map { "offline_$it" }, deliveredUserEvents.map { it.getString("name") })
    }

    @Test
    fun serverErrorsAreRetriedAndEventsAreDeliveredExactlyOnce() {
        val calls = AtomicInteger()
        responder = {
            if (calls.incrementAndGet() <= 2) MockResponse().setResponseCode(503).addHeader("Retry-After", "1")
            else MockResponse().setResponseCode(200)
        }
        init()
        repeat(3) { Analytics.track("retry_$it") }
        flushUntil(3, timeoutMillis = 15_000)
        assertTrue(calls.get() >= 3)
        val ids = deliveredUserEvents.map { it.getString("event_id") }
        assertEquals(3, ids.size)
        assertEquals(3, ids.toSet().size)
    }

    @Test
    fun rejectedBatchIsBisectedSoOnlyTheBadEventIsDropped() {
        responder = { body ->
            val events = body.getJSONArray("events")
            val bad = (0 until events.length()).any { events.getJSONObject(it).getString("name") == "bad" }
            MockResponse().setResponseCode(if (bad) 400 else 200)
        }
        init { batchSize = 8 }
        listOf("a", "b", "c", "bad", "d", "e", "f").forEach { Analytics.track(it) }
        flushUntil(6)
        Thread.sleep(300)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), deliveredUserEvents.map { it.getString("name") })
        assertEquals(1, drops.filter { it.second == DropReason.REJECTED }.sumOf { it.first })
    }

    @Test
    fun everyRequestOfASplitBatchHasItsOwnBatchId() {
        // batch_id is documented as usable for request-level idempotency, so two requests with
        // different contents must never share one (a deduping backend would discard the second).
        responder = { body ->
            MockResponse().setResponseCode(if (body.getJSONArray("events").length() > 2) 413 else 200)
        }
        init { batchSize = 8 }
        repeat(7) { Analytics.track("split_$it") }
        flushUntil(7)
        val ids = bodies.map { it.first.getString("batch_id") }
        assertEquals("batch ids: $ids", ids.size, ids.toSet().size)
    }

    @Test
    fun optOutPurgesQueueAndBlocksTracking_optInResumes() {
        val port = server.port
        server.shutdown() // keep events queued
        init()
        repeat(3) { Analytics.track("queued_$it") }
        Thread.sleep(300)
        Analytics.optOut(true)
        Analytics.track("while_opted_out")
        Thread.sleep(300)

        server = startServer(port)
        Analytics.flush()
        Thread.sleep(1_000)
        assertTrue("nothing must be sent while opted out", delivered.isEmpty())
        assertTrue(drops.any { it.second == DropReason.OPTED_OUT && it.first >= 3 })

        Analytics.optOut(false)
        Analytics.track("after_opt_in")
        flushUntil(1)
        assertEquals(listOf("after_opt_in"), deliveredUserEvents.map { it.getString("name") })
    }

    @Test
    fun fullQueueEvictsNormalEventsBeforeHighPriorityOnes() {
        val port = server.port
        server.shutdown()
        init {
            batchSize = 5
            maxQueueSize = 20
        }
        Analytics.track("vip", priority = EventPriority.HIGH)
        repeat(60) { Analytics.track("filler_$it") }
        Thread.sleep(1_000)
        server = startServer(port)
        flushUntil(1)
        Thread.sleep(500)
        Analytics.flush()
        Thread.sleep(500)
        val names = deliveredUserEvents.map { it.getString("name") }
        assertTrue("vip kept: $names", "vip" in names)
        assertTrue("queue bounded: ${names.size}", names.size <= 20)
        assertTrue(drops.any { it.second == DropReason.QUEUE_FULL })
        assertTrue(delivered.any { it.getString("name") == "sdk_events_dropped" })
    }

    @Test
    fun concurrentTrackingFromManyThreadsLosesNothing() {
        init { batchSize = 100 }
        val threads = (0 until 8).map { t -> thread { repeat(250) { Analytics.track("t${t}_$it") } } }
        threads.forEach { it.join() }
        flushUntil(2_000, timeoutMillis = 30_000)
        val events = deliveredUserEvents
        assertEquals(2_000, events.size)
        assertEquals(2_000, events.map { it.getString("event_id") }.toSet().size)
        assertEquals(2_000, events.map { it.getLong("seq") }.toSet().size)
        // Per-thread order is preserved.
        for (t in 0 until 8) {
            val mine = events.map { it.getString("name") }.filter { it.startsWith("t${t}_") }
            assertEquals((0 until 250).map { "t${t}_$it" }, mine)
        }
    }

    // ------------------------------------------------------------------ background delivery & lifecycle

    @Test
    fun uploadWorkerRetriesUntilInitialized_thenDrainsTheQueue() {
        val worker = { TestListenableWorkerBuilder<AnalyticsUploadWorker>(context).build() }
        assertTrue(runBlocking { worker().doWork() } is ListenableWorker.Result.Retry)

        val port = server.port
        server.shutdown()
        init()
        repeat(3) { Analytics.track("bg_$it") }
        Thread.sleep(500)
        assertTrue("server down -> retry", runBlocking { worker().doWork() } is ListenableWorker.Result.Retry)

        server = startServer(port)
        assertTrue(runBlocking { worker().doWork() } is ListenableWorker.Result.Success)
        assertEquals(3, deliveredUserEvents.size)
    }

    /**
     * Real backgrounding. (ActivityScenario.moveToState(CREATED) is not: it covers the activity with
     * another activity of this same process, so the app stays in the foreground.)
     */
    private fun pressHome() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("input keyevent KEYCODE_HOME").close()
    }

    @Test
    fun backgroundingTheAppFlushesQueuedEvents() {
        init()
        val scenario = ActivityScenario.launch(TestActivity::class.java)
        Thread.sleep(1_000) // let the foreground-triggered flush finish
        Analytics.track("in_foreground")
        Thread.sleep(500)
        assertTrue("no trigger yet (batch 50, interval 1h)", deliveredUserEvents.isEmpty())
        pressHome()
        waitUntil(message = "background flush") { deliveredUserEvents.isNotEmpty() }
        assertEquals("in_foreground", deliveredUserEvents.single().getString("name"))
        scenario.close()
    }

    @Test
    fun lifecycleTrackerReportsOneForegroundAndBackground_rotationIsNotATransition() {
        val foregrounds = AtomicInteger()
        val backgrounds = AtomicInteger()
        val tracker = AppLifecycleTracker({ foregrounds.incrementAndGet() }, { backgrounds.incrementAndGet() })
        val app = context.applicationContext as android.app.Application
        tracker.register(app)
        try {
            val scenario = ActivityScenario.launch(TestActivity::class.java)
            assertEquals(1, foregrounds.get())
            scenario.recreate() // configuration change
            assertEquals("recreate must not report foreground again", 1, foregrounds.get())
            assertEquals("recreate must not report background", 0, backgrounds.get())
            pressHome()
            waitUntil(message = "background") { backgrounds.get() == 1 }
            ActivityScenario.launch(TestActivity::class.java).close()
            assertEquals(2, foregrounds.get())
            scenario.close()
        } finally {
            app.unregisterActivityLifecycleCallbacks(tracker)
        }
    }

    @Test
    fun initTwiceIsIgnoredAndInvalidConfigFailsFast() {
        init()
        init { batchSize = 1 }
        assertNotNull(Analytics.clientOrNull())
        assertEquals(50, Analytics.clientOrNull()!!.config.batchSize)
        val failure = runCatching { AnalyticsConfig("ftp://nope") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(runCatching { AnalyticsConfig("https://x") { batchSize = 0 } }.exceptionOrNull() is IllegalArgumentException)
    }
}
