package com.example.analytics.storage

import android.content.Context

/**
 * Database file naming. Each process gets its own queue file, e.g. `analytics_queue_main.db` and
 * `analytics_queue_remote.db` for `com.app:remote`; all processes share `analytics_meta.db`.
 */
internal object DatabaseFiles {
    const val META = "analytics_meta.db"
    private const val QUEUE_PREFIX = "analytics_queue_"
    private const val SUFFIX = ".db"

    fun queueFor(processName: String, packageName: String): String {
        val suffix = when {
            processName == packageName -> "main"
            processName.startsWith("$packageName:") -> processName.removePrefix("$packageName:")
            else -> processName
        }
        return QUEUE_PREFIX + suffix.replace(Regex("[^A-Za-z0-9_]"), "_") + SUFFIX
    }

    /** Queue files of every process that has ever tracked an event. */
    fun allQueues(context: Context): List<String> =
        context.databaseList().filter { it.startsWith(QUEUE_PREFIX) && it.endsWith(SUFFIX) }
}
