package com.example.analytics.views

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.analytics.tracking.Cancellable
import com.example.analytics.tracking.ImpressionConfig
import com.example.analytics.tracking.ScreenEventSink
import com.example.analytics.tracking.ScreenVisitTracker
import com.example.analytics.tracking.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class ViewTrackingTest {

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

    /** 20 rows of exactly 100px. */
    private class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            object : RecyclerView.ViewHolder(View(parent.context).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100) }) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
        override fun getItemCount() = 20
    }

    private val scheduler = FakeScheduler()
    private val sink = RecordingSink()
    private val tracker = ScreenVisitTracker("list", ImpressionConfig(), scheduler, { scheduler.now }, sink)

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    /** 240px viewport: rows 0 and 1 fully visible, row 2 at 40% (below the 50% threshold). */
    private fun setUpList(): RecyclerView {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = RowsAdapter()
        }
        activity.setContentView(FrameLayout(activity).apply { addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 240)) })
        tracker.startVisit()
        list.trackImpressions(tracker, itemKey = { "row$it" })
        idle()
        return list
    }

    private fun impressed() = sink.impressions.map { it.first }

    @Test
    fun `recycler items need half visibility for a second`() {
        setUpList()
        scheduler.advance(1_000)
        assertEquals(listOf("row0", "row1"), impressed())
        assertEquals(0, sink.impressions.first().second["position"])
    }

    @Test
    fun `scrolling impresses newly visible rows once, recycled rows don't repeat`() {
        val list = setUpList()
        scheduler.advance(1_000)
        list.scrollBy(0, 160) // viewport 160..400: row1 40%, rows 2-3 full
        idle()
        scheduler.advance(1_000)
        list.scrollBy(0, 1_000) // far down; rows 0-3 recycled
        idle()
        list.scrollBy(0, -1_160) // back to top
        idle()
        scheduler.advance(1_000)
        assertEquals(listOf("row0", "row1", "row2", "row3"), impressed().take(4))
        assertEquals(impressed().size, impressed().toSet().size) // never twice in a visit
        assertTrue("rows passed through quickly are not impressed", "row8" !in impressed())
    }

    @Test
    fun `tracked click is linked to the row's impression`() {
        val list = setUpList()
        scheduler.advance(1_000)
        val row0 = list.getChildAt(0)
        var clicked = false
        row0.setTrackedOnClickListener(tracker, "row_click", key = "row0") { clicked = true }
        row0.performClick()
        assertTrue(clicked)
        assertEquals(sink.impressions.first { it.first == "row0" }.second["impression_id"], sink.clicks.single()["impression_id"])
    }

    @Test
    fun `screen visit follows the activity lifecycle and survives rotation`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContentView(FrameLayout(activity))
        // decorView, as documented: its context is not the Activity on API 24+.
        val tracked = trackScreen("feed", activity, activity, activity.window.decorView, pauseWhenWindowUnfocused = false)
        val firstVisit = tracked.visitId
        assertTrue("visit started at ON_START", firstVisit != null)

        controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
        })
        val rotated = controller.get()
        val retracked = trackScreen("feed", rotated, rotated, rotated.window.decorView, pauseWhenWindowUnfocused = false)
        assertTrue("same tracker retained across rotation", retracked === tracked)
        assertEquals("rotation keeps the visit", firstVisit, retracked.visitId)

        controller.pause().stop()
        assertEquals(null, retracked.visitId)
        controller.start().resume()
        assertTrue(retracked.visitId != null && retracked.visitId != firstVisit)
    }
}
