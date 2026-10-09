package com.example.analytics.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RemoteConfigTest {

    @Test
    fun `empty config means everything on with local settings`() {
        val config = RemoteConfig.parse("{}")
        assertTrue(config.trackingEnabled)
        assertTrue(config.uploadsEnabled)
        assertEquals(1.0, config.sampleRate, 0.0)
        assertNull(config.batchSize)
        assertNull(config.flushIntervalMillis)
    }

    @Test
    fun `values are parsed and out-of-range values clamped`() {
        val config = RemoteConfig.parse(
            """{"tracking_enabled":false,"uploads_enabled":false,"sample_rate":7,"batch_size":100000,
               "flush_interval_seconds":1,"ttl_seconds":5,"event_sample_rates":{"scroll":-1}}""",
        )
        assertFalse(config.trackingEnabled)
        assertFalse(config.uploadsEnabled)
        assertEquals(1.0, config.sampleRate, 0.0)
        assertEquals(500, config.batchSize)
        assertEquals(5_000L, config.flushIntervalMillis)
        assertEquals(60_000L, config.ttlMillis)
        assertEquals(0.0, config.sampleRateFor("scroll"), 0.0)
        assertEquals(1.0, config.sampleRateFor("other"), 0.0)
    }

    @Test
    fun `non-numeric values are ignored instead of coerced to zero`() {
        val config = RemoteConfig.parse("""{"batch_size":"fifty","flush_interval_seconds":null}""")
        assertNull(config.batchSize)
        assertNull(config.flushIntervalMillis)
    }

    @Test
    fun `blocked events support exact names and prefix wildcards`() {
        val config = RemoteConfig.parse("""{"blocked_events":["debug_*","legacy_click",""]}""")
        assertTrue(config.isBlocked("debug_fps"))
        assertTrue(config.isBlocked("legacy_click"))
        assertFalse(config.isBlocked("legacy_click_v2"))
        assertFalse(config.isBlocked("purchase"))
    }

    @Test(expected = org.json.JSONException::class)
    fun `malformed json throws so the previous config is kept`() {
        RemoteConfig.parse("{not json")
    }

    @Test
    fun `sampling is per device, stable, and nested across rates`() {
        val devices = List(10_000) { UUID.randomUUID().toString() }
        val atHalf = devices.filter { Sampling.isSampledIn(it, 0.5) }.toSet()
        val atTenth = devices.filter { Sampling.isSampledIn(it, 0.1) }.toSet()
        assertTrue(atHalf.size in 4_700..5_300)
        assertTrue(atTenth.size in 850..1_150)
        assertTrue(atHalf.containsAll(atTenth)) // lowering the rate only removes devices
        assertEquals(atHalf, devices.filter { Sampling.isSampledIn(it, 0.5) }.toSet()) // deterministic
        assertTrue(devices.none { Sampling.isSampledIn(it, 0.0) })
        assertTrue(devices.all { Sampling.isSampledIn(it, 1.0) })
    }
}
