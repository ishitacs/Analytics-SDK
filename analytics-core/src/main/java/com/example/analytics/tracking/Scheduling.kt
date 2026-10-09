package com.example.analytics.tracking

import android.os.Handler
import android.os.Looper

fun interface Cancellable {
    fun cancel()
}

/** Runs delayed work on the thread that owns a [ScreenVisitTracker]. */
fun interface Scheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): Cancellable
}

object MainThreadScheduler : Scheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    override fun schedule(delayMillis: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return Cancellable { handler.removeCallbacks(runnable) }
    }
}
