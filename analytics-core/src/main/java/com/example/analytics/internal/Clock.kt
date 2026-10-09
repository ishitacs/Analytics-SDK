package com.example.analytics.internal

import android.os.SystemClock

internal interface Clock {
    /** Wall clock; can jump when the user or network changes the device time. */
    fun currentTimeMillis(): Long

    /** Monotonic since boot; immune to time changes, used for ordering and intervals. */
    fun elapsedRealtime(): Long

    object System : Clock {
        override fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
        override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    }
}
