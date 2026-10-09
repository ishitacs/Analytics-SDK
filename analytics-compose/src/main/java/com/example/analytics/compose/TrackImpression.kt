package com.example.analytics.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import com.example.analytics.tracking.ScreenVisitTracker
import kotlin.math.abs

/**
 * Tracks an impression for this element inside a [TrackScreen]: once per screen visit, after it has
 * been at least half visible (per [com.example.analytics.tracking.ImpressionConfig]) continuously
 * for the configured duration.
 *
 * Visibility is the fraction of the element's area inside the window after clipping by every
 * ancestor (so a lazy list's viewport counts). Overlapping siblings drawn on top (e.g. a toolbar
 * overlaid in a Box) and alpha are not considered.
 *
 * @param key unique within the screen; the impression dedupe key (e.g. the item id).
 */
fun Modifier.trackImpression(
    key: String,
    name: String = ScreenVisitTracker.DEFAULT_IMPRESSION_NAME,
    properties: Map<String, Any?> = emptyMap(),
): Modifier = this then ImpressionElement(key, name, properties)

private data class ImpressionElement(
    val key: String,
    val name: String,
    val properties: Map<String, Any?>,
) : ModifierNodeElement<ImpressionNode>() {
    override fun create() = ImpressionNode(key, name, properties)

    override fun update(node: ImpressionNode) = node.update(key, name, properties)

    override fun InspectorInfo.inspectableProperties() {
        this.name = "trackImpression"
        this.properties["key"] = key
        this.properties["name"] = this@ImpressionElement.name
    }
}

private class ImpressionNode(
    private var key: String,
    private var name: String,
    private var properties: Map<String, Any?>,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    private var tracker: ScreenVisitTracker? = null
    private var lastReported = -1f

    override fun onDetach() {
        tracker?.onItemRemoved(key)
        tracker = null
        lastReported = -1f
    }

    fun update(key: String, name: String, properties: Map<String, Any?>) {
        if (key != this.key) {
            tracker?.onItemRemoved(this.key)
            lastReported = -1f
        }
        this.key = key
        this.name = name
        this.properties = properties
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val current = currentValueOf(LocalScreenVisitTracker)
        if (current !== tracker) {
            tracker?.onItemRemoved(key)
            tracker = current
            lastReported = -1f
        }
        val tracker = current ?: return
        val fraction = visibleFraction(coordinates)
        // Called every frame while scrolling; only report changes that matter.
        if (lastReported >= 0f && abs(fraction - lastReported) < MIN_DELTA) return
        lastReported = fraction
        tracker.onVisibilityChanged(key, fraction, name, properties)
    }

    private fun visibleFraction(coordinates: LayoutCoordinates): Float {
        if (!coordinates.isAttached) return 0f
        val size = coordinates.size
        val area = size.width.toFloat() * size.height.toFloat()
        if (area <= 0f) return 0f
        val visible = coordinates.boundsInWindow() // clipped by ancestors and the window
        val visibleArea = visible.width.coerceAtLeast(0f) * visible.height.coerceAtLeast(0f)
        return (visibleArea / area).coerceIn(0f, 1f)
    }

    private companion object {
        const val MIN_DELTA = 0.01f
    }
}
