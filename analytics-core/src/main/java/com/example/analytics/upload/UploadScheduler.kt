package com.example.analytics.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.analytics.AnalyticsLogger
import java.util.concurrent.TimeUnit

/**
 * Background delivery via WorkManager (JobScheduler on every supported API level), so events reach
 * the backend even if the process is killed or the app is never reopened. Survives reboots.
 *
 * Only used from the main process: WorkManager isn't initialized in secondary processes unless the
 * host opts in, and the worker sweeps every process's queue anyway.
 */
internal class UploadScheduler(private val context: Context, private val logger: AnalyticsLogger) {

    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    /** Safety net: runs roughly every 15 minutes (the platform minimum) while there is network. */
    fun schedulePeriodic() = withWorkManager {
        val request = PeriodicWorkRequestBuilder<AnalyticsUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        it.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Called when the app is backgrounded: a prompt flush that outlives the process. */
    fun scheduleOneTime() = withWorkManager {
        val request = OneTimeWorkRequestBuilder<AnalyticsUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        it.enqueueUniqueWork(ONE_TIME_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private inline fun withWorkManager(block: (WorkManager) -> Unit) {
        try {
            block(WorkManager.getInstance(context))
        } catch (e: Exception) {
            // Host disabled WorkManager's default initializer without providing a Configuration.
            logger.warn("WorkManager unavailable; background uploads disabled", e)
        }
    }

    private companion object {
        const val PERIODIC_WORK = "com.example.analytics.upload.periodic"
        const val ONE_TIME_WORK = "com.example.analytics.upload.once"
    }
}
