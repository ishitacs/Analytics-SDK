package com.example.analytics.views

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView
import com.example.analytics.tracking.ScreenVisitTracker

/**
 * Impression tracking for RecyclerView items: reports each attached child's visible fraction on
 * scroll and layout (including adapter updates), and tells the tracker when a child is recycled.
 *
 * Keys come from [itemKey] by adapter position, so a view holder rebound to another item is
 * handled; positions with a null key (headers, spinners) are skipped. Works with any LayoutManager
 * and with nested RecyclerViews (attach one tracker per list, keys unique within the screen).
 */
class RecyclerViewImpressionTracker internal constructor(
    private val recyclerView: RecyclerView,
    private val tracker: ScreenVisitTracker,
    private val itemKey: (position: Int) -> String?,
    private val itemName: (position: Int) -> String,
    private val itemProperties: (position: Int) -> Map<String, Any?>,
) {
    private val keysByView = HashMap<View, String>()
    private val scratch = Rect()

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = evaluate()
    }

    private val childListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) = Unit // measured later; layout listener evaluates

        override fun onChildViewDetachedFromWindow(view: View) {
            keysByView.remove(view)?.let(tracker::onItemRemoved)
        }
    }

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { evaluate() }

    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            recyclerView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            evaluate()
        }

        override fun onViewDetachedFromWindow(v: View) {
            val observer = recyclerView.viewTreeObserver
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
            keysByView.values.forEach(tracker::onItemRemoved)
            keysByView.clear()
        }
    }

    internal fun attach(): RecyclerViewImpressionTracker {
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnChildAttachStateChangeListener(childListener)
        recyclerView.addOnAttachStateChangeListener(attachListener)
        if (recyclerView.isAttachedToWindow) attachListener.onViewAttachedToWindow(recyclerView)
        return this
    }

    /** Stop tracking (e.g. when swapping adapters to a list that shouldn't be tracked). */
    fun detach() {
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.removeOnChildAttachStateChangeListener(childListener)
        recyclerView.removeOnAttachStateChangeListener(attachListener)
        attachListener.onViewDetachedFromWindow(recyclerView)
    }

    /** Re-check visibility now; called automatically on scroll and layout. */
    fun evaluate() {
        for (index in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(index)
            val position = recyclerView.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val key = itemKey(position) ?: continue
            val previous = keysByView.put(child, key)
            if (previous != null && previous != key) tracker.onItemRemoved(previous)
            tracker.onVisibilityChanged(key, child.visibleFraction(scratch), itemName(position), itemProperties(position))
        }
    }
}

/**
 * Track item impressions of this list within [tracker]'s screen.
 *
 * ```
 * recyclerView.trackImpressions(tracker, itemKey = { adapter.items[it].id }, itemName = { "product_impression" })
 * ```
 */
@JvmOverloads
fun RecyclerView.trackImpressions(
    tracker: ScreenVisitTracker,
    itemKey: (position: Int) -> String?,
    itemName: (position: Int) -> String = { ScreenVisitTracker.DEFAULT_IMPRESSION_NAME },
    itemProperties: (position: Int) -> Map<String, Any?> = { mapOf("position" to it) },
): RecyclerViewImpressionTracker =
    RecyclerViewImpressionTracker(this, tracker, itemKey, itemName, itemProperties).attach()
