package com.example.analytics.network

enum class ResponseAction {
    /** Delivered; delete from the queue. */
    SUCCESS,

    /** Transient; keep, back off, try again. Counts against maxAttempts. */
    RETRY,

    /** Batch too big; split in half and resend. A single event that is still too big is dropped. */
    PAYLOAD_TOO_LARGE,

    /**
     * Permanently rejected. A multi-event batch is bisected to isolate the bad event(s) so one
     * malformed event can't take the rest of the batch down with it.
     */
    REJECT,
}

/** Maps an HTTP response to what the uploader should do with the batch. */
fun interface ResponseClassifier {
    fun classify(response: HttpResponse): ResponseAction
}

class DefaultResponseClassifier : ResponseClassifier {
    override fun classify(response: HttpResponse): ResponseAction = when (response.code) {
        in 200..299 -> ResponseAction.SUCCESS
        413 -> ResponseAction.PAYLOAD_TOO_LARGE
        // 401/403: the token may be refreshed by headerProvider before the next attempt.
        401, 403, 408, 429 -> ResponseAction.RETRY
        in 500..599 -> ResponseAction.RETRY
        in 400..499 -> ResponseAction.REJECT
        // Unexpected 1xx/3xx/-1: likely misconfiguration; keep the data (bounded by TTL) rather than lose it.
        else -> ResponseAction.RETRY
    }
}
