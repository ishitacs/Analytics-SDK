package com.example.analytics.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.example.analytics.Analytics

/**
 * Wraps [onClick] so the tap is tracked first. Inside a [TrackScreen], the click carries `screen`,
 * `screen_visit_id`, `item_key` and — if the item was impressed this visit — `impression_id`, and
 * accidental double taps are tracked once. [onClick] itself always runs.
 *
 * ```
 * Card(Modifier.clickable(onClick = rememberTrackedClick("product_click", key = product.id) { open(product) }))
 * ```
 */
@Composable
fun rememberTrackedClick(
    name: String,
    key: String? = null,
    properties: Map<String, Any?> = emptyMap(),
    onClick: () -> Unit,
): () -> Unit {
    val tracker = LocalScreenVisitTracker.current
    val currentProperties by rememberUpdatedState(properties)
    val currentOnClick by rememberUpdatedState(onClick)
    return remember(tracker, name, key) {
        {
            if (tracker != null) {
                tracker.trackClick(name, key, currentProperties)
            } else {
                Analytics.trackClick(name, if (key == null) currentProperties else currentProperties + ("item_key" to key))
            }
            currentOnClick()
        }
    }
}
