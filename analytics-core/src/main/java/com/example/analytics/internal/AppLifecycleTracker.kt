package com.example.analytics.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Foreground/background detection from started-activity counting. Avoids a lifecycle-process
 * dependency and works in any process that hosts activities. Rotation doesn't count as background.
 */
internal class AppLifecycleTracker(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) : Application.ActivityLifecycleCallbacks {

    private var startedActivities = 0

    // Set when the last started activity stopped for a configuration change: its recreated
    // instance's onStart must not count as a new foreground.
    private var changingConfigurations = false

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        if (startedActivities == 1) {
            if (changingConfigurations) changingConfigurations = false else onForeground()
        }
    }

    override fun onActivityStopped(activity: Activity) {
        // Init may happen after an activity already started; never go negative.
        if (startedActivities == 0) return
        startedActivities--
        if (startedActivities == 0) {
            if (activity.isChangingConfigurations) changingConfigurations = true else onBackground()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
