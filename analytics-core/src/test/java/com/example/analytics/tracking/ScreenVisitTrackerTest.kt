package com.example.analytics.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenVisitTrackerTest {

    /** Virtual-time scheduler: [advance] runs whatever came due. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

        override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
            val cancelled = booleanArrayOf(false)
            tasks += Triple(now + delayMillis, action, cancelled)
            return Cancellable { cancelled[0] = true }
        }

        fun advance(millis: Long) {
            now += millis
            val due = tasks.filter { it.first <= now }
            tasks.removeAll(due)
            due.filterNot { it.third[0] }.forEach { it.second() }
        }
    }

    private class RecordingSink : ScreenEventSink {
        val screens = mutableListOf<Map<String, Any?>>()
        val impressions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val clicks = mutableListOf<Map<String, Any?>>()

        override fun screen(name: String, properties: Map<String, Any?>) {
            screens += properties
        }

        override fun impression(name: String, itemKey: String, properties: Map<String, Any?>) {
            impressions += itemKey to properties
        }

        override fun click(name: String, properties: Map<String, Any?>) {
            clicks += properties
        }
    }

    private val scheduler = FakeScheduler()
    private val sink = RecordingSink()
    private val tracker = ScreenVisitTracker("feed", ImpressionConfig(), scheduler, { scheduler.now }, sink)

    private fun impressedKeys() = sink.impressions.map { it.first }

    @Test
    fun `impression needs half visibility for a full second`() {
        tracker.startVisit()
        tracker.onVisibilityChanged("a", 0.6f)
        tracker.onVisibilityChanged("b", 0.4f)
        scheduler.advance(999)
        assertTrue(sink.impressions.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf("a"), impressedKeys())
    }

    @Test
    fun `scrolling past quickly does not count`() {
        tracker.startVisit()
        tracker.onVisibilityChanged("a", 1f)
        scheduler.advance(400)
        tracker.onVisibilityChanged("a", 0.2f)
        scheduler.advance(2_000)
        assertTrue(sink.impressions.isEmpty())
    }

    @Test
    fun `visibility timer restarts after dipping below threshold`() {
        tracker.startVisit()
        tracker.onVisibilityChanged("a", 1f)
        scheduler.advance(800)
        tracker.onVisibilityChanged("a", 0.3f)
        tracker.onVisibilityChanged("a", 0.9f)
        scheduler.advance(800)
        assertTrue(sink.impressions.isEmpty())
        scheduler.advance(200)
        assertEquals(listOf("a"), impressedKeys())
    }

    @Test
    fun `once per visit, again in the next visit`() {
        tracker.startVisit()
        tracker.onVisibilityChanged("a", 1f)
        scheduler.advance(1_000)
        tracker.onItemRemoved("a") // scrolled away and disposed
        tracker.onVisibilityChanged("a", 1f) // scrolled back
        scheduler.advance(5_000)
        assertEquals(1, sink.impressions.size)

        tracker.endVisit()
        tracker.startVisit()
        scheduler.advance(1_000)
        assertEquals(2, sink.impressions.size)
        assertNotEquals(sink.impressions[0].second["screen_visit_id"], sink.impressions[1].second["screen_visit_id"])
    }

    @Test
    fun `nothing is counted while paused, and timers restart on resume`() {
        tracker.startVisit()
        tracker.onVisibilityChanged("a", 1f)
        scheduler.advance(700)
        tracker.setPaused(PauseReason.WINDOW_FOCUS, true) // dialog opened
        scheduler.advance(5_000)
        assertTrue(sink.impressions.isEmpty())
        tracker.setPaused(PauseReason.WINDOW_FOCUS, false)
        scheduler.advance(999)
        assertTrue(sink.impressions.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf("a"), impressedKeys())
    }

    @Test
    fun `paused by two reasons resumes only when both clear`() {
        tracker.startVisit()
        tracker.onVisibilityChanged("a", 1f)
        tracker.setPaused(PauseReason.LIFECYCLE, true)
        tracker.setPaused(PauseReason.WINDOW_FOCUS, true)
        tracker.setPaused(PauseReason.LIFECYCLE, false)
        scheduler.advance(2_000)
        assertTrue(sink.impressions.isEmpty())
        tracker.setPaused(PauseReason.WINDOW_FOCUS, false)
        scheduler.advance(1_000)
        assertEquals(listOf("a"), impressedKeys())
    }

    @Test
    fun `no impressions outside a visit`() {
        tracker.onVisibilityChanged("a", 1f)
        scheduler.advance(5_000)
        assertTrue(sink.impressions.isEmpty())
        tracker.startVisit() // items already on screen start counting when the visit starts
        scheduler.advance(1_000)
        assertEquals(listOf("a"), impressedKeys())
    }

    @Test
    fun `screen view carries the visit id shared by impressions and clicks`() {
        tracker.startVisit(mapOf("source" to "deeplink"))
        tracker.onVisibilityChanged("a", 1f, name = "product_impression", properties = mapOf("position" to 0))
        scheduler.advance(1_000)
        tracker.trackClick("product_click", key = "a")

        val visitId = sink.screens.single()["screen_visit_id"]
        assertEquals("deeplink", sink.screens.single()["source"])
        val impression = sink.impressions.single().second
        val click = sink.clicks.single()
        assertEquals(visitId, impression["screen_visit_id"])
        assertEquals(visitId, click["screen_visit_id"])
        assertEquals(0, impression["position"])
        assertEquals("feed", click["screen"])
        assertEquals("a", click["item_key"])
        assertEquals(impression["impression_id"], click["impression_id"])
    }

    @Test
    fun `click on an item never seen has no impression id`() {
        tracker.startVisit()
        tracker.trackClick("tap", key = "unseen")
        assertFalse(sink.clicks.single().containsKey("impression_id"))
    }

    @Test
    fun `double taps are debounced per target`() {
        tracker.startVisit()
        tracker.trackClick("tap", key = "a")
        scheduler.now += 100
        tracker.trackClick("tap", key = "a") // double tap: ignored
        tracker.trackClick("tap", key = "b") // different target: counted
        scheduler.now += 300
        tracker.trackClick("tap", key = "a") // deliberate second tap: counted
        assertEquals(listOf("a", "b", "a"), sink.clicks.map { it["item_key"] })
    }
}
