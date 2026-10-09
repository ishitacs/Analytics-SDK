package com.example.analyticslib

import android.app.Application
import com.example.analytics.Analytics
import com.example.analytics.AnalyticsConfig
import com.example.analytics.EventType
import com.example.analyticslib.demo.DebugState
import com.example.analyticslib.demo.MockBackend
import com.example.analyticslib.demo.WarehouseBackend
import kotlin.time.Duration.Companion.seconds

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Runs in every process (main and :remote), as the SDK requires.
        Analytics.init(
            this,
            AnalyticsConfig("https://mock.analytics.local/v1/events") {
                header("X-App-Key", "demo-key")
                networkClient = MockBackend
                batchSize = 10
                flushInterval = 15.seconds
                listener = DebugState.listener
                debug = true
                remoteConfigUrl = "https://mock.analytics.local/v1/config"
                // Second backend that only wants navigation and clicks, not impressions.
                destination("warehouse", "https://mock.warehouse.local/collect") {
                    networkClient = WarehouseBackend
                    filter = { it.type == EventType.CLICK || it.type == EventType.SCREEN }
                }
            },
        )
        Analytics.setSuperProperties(mapOf("demo_build" to true))
    }
}
