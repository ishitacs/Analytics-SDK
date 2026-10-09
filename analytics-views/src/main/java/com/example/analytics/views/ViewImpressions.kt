package com.example.analytics.views

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import com.example.analytics.Analytics
import com.example.analytics.tracking.ScreenVisitTracker

/**
 * Fraction (0..1) of this view's area on screen, after clipping by every ancestor and the window.
 * Ignores siblings drawn on top, alpha and transforms.
 */
internal fun View.visibleFraction(scratch: Rect): Float {
    if (!isAttachedToWindow || !isShown || width == 0 || height == 0) return 0f
    if (!getGlobalVisibleRect(scratch)) return 0f
    return (scratch.width().toFloat() * scratch.height()) / (width.toFloat() * height)
}

/**
 * Impression tracking for one view outside a RecyclerView (banner, hero card, item in a
 * ScrollView). Re-evaluated on any scroll or layout in the window; stops when the view detaches.
 */
class ViewImpressionTracker internal constructor(
    private val view: View,
    private val tracker: ScreenVisitTracker,
    private val key: String,
    private val name: String,
    private val properties: Map<String, Any?>,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {

    private val scratch = Rect()

    internal fun attach(): ViewImpressionTracker {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        return this
    }

    /** Stop tracking this view. */
    fun detach() {
        view.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(view)
    }

    override fun onViewAttachedToWindow(v: View) {
        view.viewTreeObserver.addOnScrollChangedListener(this)
        view.viewTreeObserver.addOnGlobalLayoutListener(this)
        evaluate()
    }

    override fun onViewDetachedFromWindow(v: View) {
        val observer = view.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnScrollChangedListener(this)
            observer.removeOnGlobalLayoutListener(this)
        }
        tracker.onItemRemoved(key)
    }

    override fun onScrollChanged() = evaluate()

    override fun onGlobalLayout() = evaluate()

    private fun evaluate() {
        tracker.onVisibilityChanged(key, view.visibleFraction(scratch), name, properties)
    }
}

/** Track impressions of this view within [tracker]'s screen. See [ViewImpressionTracker]. */
@JvmOverloads
fun View.trackImpression(
    tracker: ScreenVisitTracker,
    key: String,
    name: String = ScreenVisitTracker.DEFAULT_IMPRESSION_NAME,
    properties: Map<String, Any?> = emptyMap(),
): ViewImpressionTracker = ViewImpressionTracker(this, tracker, key, name, properties).attach()

/**
 * Click listener that tracks the tap first (debounced, linked to the item's impression inside a
 * tracked screen) and then always calls [listener]. [properties] is evaluated per click.
 */
@JvmOverloads
fun View.setTrackedOnClickListener(
    tracker: ScreenVisitTracker?,
    name: String,
    key: String? = null,
    properties: () -> Map<String, Any?> = { emptyMap() },
    listener: View.OnClickListener,
) {
    setOnClickListener { view ->
        val props = properties()
        if (tracker != null) {
            tracker.trackClick(name, key, props)
        } else {
            Analytics.trackClick(name, if (key == null) props else props + ("item_key" to key))
        }
        listener.onClick(view)
    }
}
