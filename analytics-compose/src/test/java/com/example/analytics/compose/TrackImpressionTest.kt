package com.example.analytics.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.analytics.tracking.Cancellable
import com.example.analytics.tracking.ImpressionConfig
import com.example.analytics.tracking.ScreenEventSink
import com.example.analytics.tracking.ScreenVisitTracker
import com.example.analytics.tracking.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackImpressionTest {

    @get:Rule
    val rule = createComposeRule()

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
        val screens = mutableListOf<String>()
        val impressions = mutableListOf<Pair<String, Map<String, Any?>>>()
        val clicks = mutableListOf<Map<String, Any?>>()
        override fun screen(name: String, properties: Map<String, Any?>) {
            screens += name
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
    private lateinit var scroll: ScrollState
    private var clicked = false

    /** 100dp viewport over five 60dp items: item0 fully visible, item1 2/3 visible, the rest hidden. */
    private fun setFeed() = rule.setContent {
        TrackScreen(tracker, pauseWhenWindowUnfocused = false) {
            scroll = rememberScrollState()
            Column(Modifier.height(100.dp).verticalScroll(scroll)) {
                repeat(5) { i ->
                    Box(
                        Modifier
                            .height(60.dp)
                            .fillMaxWidth()
                            .testTag("item$i")
                            .trackImpression("item$i", properties = mapOf("position" to i))
                            .clickable(onClick = rememberTrackedClick("tap", key = "item$i") { clicked = true }),
                    )
                }
            }
        }
    }

    private fun impressed() = sink.impressions.map { it.first }

    @Test
    fun `screen view is tracked when the visit starts`() {
        setFeed()
        rule.runOnIdle { assertEquals(listOf("feed"), sink.screens) }
    }

    @Test
    fun `only items at least half visible for a second are impressed`() {
        setFeed()
        rule.runOnIdle {
            scheduler.advance(999)
            assertTrue(sink.impressions.isEmpty())
            scheduler.advance(1)
        }
        rule.runOnIdle { assertEquals(listOf("item0", "item1"), impressed()) }
    }

    @Test
    fun `scrolling reveals and impresses new items exactly once`() {
        setFeed()
        rule.runOnIdle { scheduler.advance(1_000) }
        val px = with(rule.density) { 120.dp.toPx() }
        rule.runOnIdle { scroll.dispatchRawDelta(px) } // viewport now 120..220dp: item2 full, item3 2/3
        rule.runOnIdle { scheduler.advance(1_000) }
        rule.runOnIdle { scroll.dispatchRawDelta(-px) } // back to the top: no repeats this visit
        rule.runOnIdle { scheduler.advance(1_000) }
        rule.runOnIdle { assertEquals(listOf("item0", "item1", "item2", "item3"), impressed()) }
    }

    @Test
    fun `click is tracked, linked to the impression, and still runs the handler`() {
        setFeed()
        rule.runOnIdle { scheduler.advance(1_000) }
        rule.onNodeWithTag("item0").performClick()
        rule.runOnIdle {
            assertTrue(clicked)
            val click = sink.clicks.single()
            assertEquals("item0", click["item_key"])
            assertEquals(sink.impressions.first { it.first == "item0" }.second["impression_id"], click["impression_id"])
            assertEquals(0, sink.impressions.first().second["position"])
        }
    }
}
