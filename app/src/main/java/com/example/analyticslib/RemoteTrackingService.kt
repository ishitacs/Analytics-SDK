package com.example.analyticslib

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.example.analytics.Analytics

/** Lives in the `:remote` process; its events go to `analytics_queue_remote.db`. */
class RemoteTrackingService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Analytics.track("remote_ping", mapOf("pid" to Process.myPid()))
        Analytics.flush()
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
