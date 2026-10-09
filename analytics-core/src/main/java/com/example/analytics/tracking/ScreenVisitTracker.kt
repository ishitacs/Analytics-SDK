package com.example.analytics.tracking

import android.os.SystemClock
import com.example.analytics.Analytics
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What counts as an impression. Defaults follow the common IAB-style rule: at least half the item
 * on screen, continuously, for at least one second.
 */
class ImpressionConfig(
    val minVisibleFraction: Float = 0.5f,
    val minVisibleDuration: Duration = 1.seconds,
    /** Taps on the same item within this window count as one click (accidental double taps). */
    val clickDebounce: Duration = 300.milliseconds,
) {
    init {
        require(minVisibleFraction > 0f && minVisibleFraction <= 1f) { "minVisibleFraction must be in (0, 1]" }
        require(!minVisibleDuration.isNegative()) { "minVisibleDuration must be >= 0" }
    }
}

enum class PauseReason {
    /** Host screen paused (another activity/transparent screen on top, multi-window focus loss). */
    LIFECYCLE,

    /** Window lost focus, e.g. a dialog or the notification shade is covering it. */
    WINDOW_FOCUS,
}

/** Where a [ScreenVisitTracker] sends events. Defaults to [Analytics]; replace in tests. */
interface ScreenEventSink {
    fun screen(name: String, properties: Map<String, Any?>)
    fun impression(name: String, itemKey: String, properties: Map<String, Any?>)
    fun click(name: String, properties: Map<String, Any?>)
}

object AnalyticsSink : ScreenEventSink {
    override fun screen(name: String, properties: Map<String, Any?>) = Analytics.screen(name, properties)
    override fun impression(name: String, itemKey: String, properties: Map<String, Any?>) =
        Analytics.trackImpression(name, itemKey, properties)
    override fun click(name: String, properties: Map<String, Any?>) = Analytics.trackClick(name, properties)
}

/**
 * Impression and click logic for one screen, independent of the UI toolkit: the Compose and View
 * integrations only report visibility fractions; this class decides what counts.
 *
 * A **visit** runs from [startVisit] to [endVisit] (the integrations map these to ON_START/ON_STOP).
 * Each item key produces at most one impression per visit. Visibility timers pause while the
 * screen is paused or covered and restart from zero when it resumes — an item must be seen
 * continuously for [ImpressionConfig.minVisibleDuration].
 *
 * Every impression and click carries `screen`, `screen_visit_id` and `item_key`; clicks also carry
 * the `impression_id` of the item's impression in this visit, so CTR is an exact join.
 *
 * Not thread-safe: call everything from the main thread (or the thread [scheduler] runs on).
 */
class ScreenVisitTracker(
    val screenName: String,
    private val config: ImpressionConfig = ImpressionConfig(),
    private val scheduler: Scheduler = MainThreadScheduler,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val sink: ScreenEventSink = AnalyticsSink,
) {
    private class Item(
        var fraction: Float,
        var name: String,
        var properties: Map<String, Any?>,
        var timer: Cancellable? = null,
    )

    private val items = HashMap<String, Item>()
    private val impressionIds = HashMap<String, String>()
    private val lastClickAt = HashMap<String, Long>()
    private val pauseReasons = HashSet<PauseReason>()

    /** Null outside a visit. */
    var visitId: String? = null
        private set

    val isActive: Boolean get() = visitId != null && pauseReasons.isEmpty()

    /** Starts a visit and tracks the screen view. No-op if a visit is already running. */
    fun startVisit(properties: Map<String, Any?> = emptyMap()) {
        if (visitId != null) return
        val id = UUID.randomUUID().toString()
        visitId = id
        impressionIds.clear()
        lastClickAt.clear()
        sink.screen(screenName, properties + (SCREEN_VISIT_ID to id))
        evaluateAll()
    }

    /** Ends the visit; items seen again in the next visit produce new impressions. */
    fun endVisit() {
        visitId = null
        cancelAllTimers()
    }

    fun setPaused(reason: PauseReason, paused: Boolean) {
        val changed = if (paused) pauseReasons.add(reason) else pauseReasons.remove(reason)
        if (!changed) return
        if (paused) cancelAllTimers() else evaluateAll()
    }

    /**
     * Report the fraction (0..1) of [key]'s area currently on screen. Call whenever it may have
     * changed (scroll, layout). [key] must be unique within the screen.
     */
    fun onVisibilityChanged(
        key: String,
        visibleFraction: Float,
        name: String = DEFAULT_IMPRESSION_NAME,
        properties: Map<String, Any?> = emptyMap(),
    ) {
        val item = items.getOrPut(key) { Item(visibleFraction, name, properties) }
        item.fraction = visibleFraction
        item.name = name
        item.properties = properties
        evaluate(key, item)
    }

    /** The item left the UI (scrolled out of a lazy list and disposed, or removed). */
    fun onItemRemoved(key: String) {
        items.remove(key)?.timer?.cancel()
    }

    /**
     * Track a click on [key] (or a non-item element when null). Repeated taps on the same target
     * within [ImpressionConfig.clickDebounce] are tracked once.
     */
    fun trackClick(name: String, key: String? = null, properties: Map<String, Any?> = emptyMap()) {
        val now = clock()
        val debounceKey = "$name|$key"
        val last = lastClickAt[debounceKey]
        if (last != null && now - last < config.clickDebounce.inWholeMilliseconds) return
        lastClickAt[debounceKey] = now

        val extra = HashMap<String, Any?>()
        extra[SCREEN] = screenName
        visitId?.let { extra[SCREEN_VISIT_ID] = it }
        if (key != null) {
            extra[ITEM_KEY] = key
            impressionIds[key]?.let { extra[IMPRESSION_ID] = it }
        }
        sink.click(name, properties + extra)
    }

    /** Whether [key] already produced an impression in the current visit. */
    fun hasImpression(key: String): Boolean = key in impressionIds

    private fun evaluateAll() {
        items.forEach { (key, item) -> evaluate(key, item) }
    }

    private fun evaluate(key: String, item: Item) {
        val eligible = isActive && key !in impressionIds && item.fraction >= config.minVisibleFraction
        if (!eligible) {
            item.timer?.cancel()
            item.timer = null
            return
        }
        if (item.timer != null) return // already counting
        item.timer = scheduler.schedule(config.minVisibleDuration.inWholeMilliseconds) { fire(key) }
    }

    private fun fire(key: String) {
        val item = items[key] ?: return
        item.timer = null
        val visit = visitId
        if (visit == null || !isActive || key in impressionIds || item.fraction < config.minVisibleFraction) return
        val impressionId = UUID.randomUUID().toString()
        impressionIds[key] = impressionId
        sink.impression(
            item.name,
            key,
            item.properties + mapOf(
                SCREEN to screenName,
                SCREEN_VISIT_ID to visit,
                IMPRESSION_ID to impressionId,
            ),
        )
    }

    private fun cancelAllTimers() {
        items.values.forEach {
            it.timer?.cancel()
            it.timer = null
        }
    }

    companion object {
        const val DEFAULT_IMPRESSION_NAME = "item_impression"
        const val SCREEN = "screen"
        const val SCREEN_VISIT_ID = "screen_visit_id"
        const val IMPRESSION_ID = "impression_id"
        const val ITEM_KEY = "item_key"
    }
}
