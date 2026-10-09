package com.example.analytics

import android.util.Log

enum class DropReason {
    /** Name blank/too long or serialized event above [PropertyLimits.maxEventBytes]. */
    INVALID,

    /** Removed by [AnalyticsConfig.beforeSend]. */
    FILTERED,

    /** On-disk queue was full; oldest lowest-priority events were evicted. */
    QUEUE_FULL,

    /** In-memory hand-off buffer was full (writer thread could not keep up). */
    BUFFER_FULL,

    /** Older than [AnalyticsConfig.eventTtl]. */
    EXPIRED,

    /** Retried [AnalyticsConfig.maxAttempts] times without success. */
    MAX_ATTEMPTS,

    /** Backend permanently rejected the event (non-retryable 4xx, or 413 for a single event). */
    REJECTED,

    /** User opted out; pending events were purged. */
    OPTED_OUT,

    /** Tracking disabled, event blocked, or device sampled out by remote config. */
    REMOTE_CONFIG,
}

/**
 * Observes the SDK's delivery pipeline. Callbacks run on SDK background threads and must be cheap;
 * exceptions thrown from them are caught and logged.
 */
interface AnalyticsListener {
    /** [queueSize]: rows queued in this process across all destinations. */
    fun onEventsQueued(queueSize: Int) {}

    /** [destination] is [Destination.DEFAULT_ID] for the main endpoint; [queueSize] is that destination's. */
    fun onBatchUploaded(destination: String, eventCount: Int, queueSize: Int) {}
    fun onUploadFailed(destination: String, eventCount: Int, error: String, willRetry: Boolean) {}
    fun onEventsDropped(count: Int, reason: DropReason) {}
}

/** Where SDK diagnostics go. Defaults to logcat; only warnings and errors unless debug is on. */
interface AnalyticsLogger {
    fun debug(message: String)
    fun warn(message: String, error: Throwable? = null)
    fun error(message: String, error: Throwable? = null)
}

class LogcatLogger(private val debugEnabled: Boolean) : AnalyticsLogger {
    override fun debug(message: String) {
        if (debugEnabled) Log.d(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }

    override fun error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
    }

    private companion object {
        const val TAG = "Analytics"
    }
}
