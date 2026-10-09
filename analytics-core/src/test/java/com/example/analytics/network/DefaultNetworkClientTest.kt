package com.example.analytics.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DefaultNetworkClientTest {

    private val server = MockWebServer().apply { start() }
    private val client = DefaultNetworkClient(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

    @After
    fun tearDown() = server.shutdown()

    private fun request(body: String = "{}") = HttpRequest(
        url = server.url("/events").toString(),
        method = "POST",
        headers = mapOf("Content-Type" to "application/json", "X-App-Key" to "abc"),
        body = body.toByteArray(),
    )

    @Test
    fun `sends body and headers and returns status`() {
        server.enqueue(MockResponse().setResponseCode(202))
        val response = client.execute(request("{\"a\":1}"))
        val recorded = server.takeRequest()
        assertEquals(202, response.code)
        assertEquals("POST", recorded.method)
        assertEquals("abc", recorded.getHeader("X-App-Key"))
        assertEquals("{\"a\":1}", recorded.body.readUtf8())
    }

    @Test
    fun `error responses expose headers case-insensitively`() {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "30").setBody("slow down"))
        val response = client.execute(request())
        assertEquals(429, response.code)
        assertEquals("30", response.header("retry-after"))
    }

    @Test
    fun `GET sends no body and returns the response body`() {
        server.enqueue(MockResponse().setBody("{\"sample_rate\":0.5}").addHeader("ETag", "\"v1\""))
        val response = client.execute(HttpRequest(server.url("/config").toString(), "GET", emptyMap(), ByteArray(0)))
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals(0L, recorded.bodySize)
        assertEquals("{\"sample_rate\":0.5}", response.body!!.toString(Charsets.UTF_8))
        assertEquals("\"v1\"", response.header("etag"))
    }

    @Test(expected = IOException::class)
    fun `unreachable host throws IOException`() {
        val url = server.url("/events").toString()
        server.shutdown()
        client.execute(HttpRequest(url, "POST", emptyMap(), ByteArray(0)))
    }
}
