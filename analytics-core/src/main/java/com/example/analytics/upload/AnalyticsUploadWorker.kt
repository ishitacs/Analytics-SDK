package com.example.analytics.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.analytics.Analytics

/** Drains every process's queue. Needs `Analytics.init` in `Application.onCreate` to find config. */
internal class AnalyticsUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val client = Analytics.clientOrNull()
            ?: return if (runAttemptCount < MAX_UNINITIALIZED_RETRIES) Result.retry() else Result.success()
        return if (client.flushAllQueues()) Result.success() else Result.retry()
    }

    private companion object {
        const val MAX_UNINITIALIZED_RETRIES = 3
    }
}
