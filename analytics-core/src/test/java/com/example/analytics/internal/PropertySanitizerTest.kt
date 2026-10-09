package com.example.analytics.internal

import com.example.analytics.PropertyLimits
import com.example.analytics.SilentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PropertySanitizerTest {

    private val sanitizer = PropertySanitizer(
        PropertyLimits(maxStringLength = 10, maxDepth = 2, maxCollectionSize = 3, maxKeyLength = 20),
        SilentLogger,
    )

    @Test
    fun `reserved keys are dropped case-insensitively`() {
        val json = sanitizer.sanitize(mapOf("event_id" to "spoof", "USER_ID" to "x", "device_id" to 1, "sku" to "A1"))
        assertEquals(setOf("sku"), json.keys().asSequence().toSet())
    }

    @Test
    fun `primitive types are preserved`() {
        val json = sanitizer.sanitize(mapOf("s" to "a", "i" to 1, "l" to 2L, "d" to 1.5, "b" to true, "n" to null))
        assertEquals("a", json.get("s"))
        assertEquals(1, json.get("i"))
        assertEquals(2L, json.get("l"))
        assertEquals(1.5, json.getDouble("d"), 0.0)
        assertEquals(true, json.get("b"))
        assertTrue(json.isNull("n"))
    }

    @Test
    fun `non-finite numbers are dropped`() {
        val json = sanitizer.sanitize(mapOf("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "ok" to 1))
        assertFalse(json.has("nan"))
        assertFalse(json.has("inf"))
        assertTrue(json.has("ok"))
    }

    @Test
    fun `strings are truncated and collections capped`() {
        val json = sanitizer.sanitize(mapOf("long" to "abcdefghijklmnop", "list" to listOf(1, 2, 3, 4, 5)))
        assertEquals("abcdefghij", json.getString("long"))
        assertEquals(3, json.getJSONArray("list").length())
    }

    @Test
    fun `nesting deeper than limit is dropped`() {
        val json = sanitizer.sanitize(mapOf("a" to mapOf("b" to mapOf("c" to 1)), "shallow" to mapOf("x" to 1)))
        assertEquals(0, json.getJSONObject("a").getJSONObject("b").length())
        assertEquals(1, json.getJSONObject("shallow").getInt("x"))
    }

    @Test
    fun `enums, arrays and unknown types are converted`() {
        val json = sanitizer.sanitize(
            mapOf("e" to Thread.State.NEW, "arr" to intArrayOf(1, 2), "obj" to StringBuilder("sb")),
        )
        assertEquals("NEW", json.getString("e"))
        assertEquals(2, json.getJSONArray("arr").length())
        assertEquals("sb", json.getString("obj"))
    }

    @Test
    fun `blank and overlong keys are dropped`() {
        val json = sanitizer.sanitize(mapOf(" " to 1, "k".repeat(21) to 2, "fine" to 3))
        assertEquals(JSONObject().put("fine", 3).toString(), json.toString())
    }

    @Test
    fun `event names are validated`() {
        assertEquals("tap", sanitizer.sanitizeName("  tap "))
        assertNull(sanitizer.sanitizeName("  "))
        assertNull(sanitizer.sanitizeName("x".repeat(129)))
    }
}
