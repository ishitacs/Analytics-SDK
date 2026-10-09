package com.example.analyticslib.demo

import com.example.analytics.AnalyticsListener
import com.example.analytics.Destination
import com.example.analytics.DropReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugSnapshot(
    val queued: Int = 0,
    val delivered: Int = 0,
    val dropped: Int = 0,
    val log: List<String> = emptyList(),
)

/** What the debug panel shows. Fed by the SDK listener and the mock backend (this process only). */
object DebugState {
    private val _state = MutableStateFlow(DebugSnapshot())
    val state: StateFlow<DebugSnapshot> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) = _state.update {
        val stamped = "${synchronized(timeFormat) { timeFormat.format(Date()) }}  $line"
        it.copy(log = (listOf(stamped) + it.log).take(MAX_LOG_LINES))
    }

    val listener = object : AnalyticsListener {
        override fun onEventsQueued(queueSize: Int) = _state.update { it.copy(queued = queueSize) }

        // Count only the main backend so "Delivered" isn't inflated by the warehouse copies.
        override fun onBatchUploaded(destination: String, eventCount: Int, queueSize: Int) {
            if (destination == Destination.DEFAULT_ID) {
                _state.update { it.copy(delivered = it.delivered + eventCount, queued = queueSize) }
            }
        }

        override fun onEventsDropped(count: Int, reason: DropReason) {
            _state.update { it.copy(dropped = it.dropped + count) }
            log("dropped $count (${reason.name.lowercase()})")
        }
    }

    private const val MAX_LOG_LINES = 5
}
