package com.example.analytics

import com.example.analytics.internal.Clock
import com.example.analytics.network.HttpRequest
import com.example.analytics.network.HttpResponse
import com.example.analytics.network.NetworkClient
import org.json.JSONObject
import java.io.IOException
import java.util.zip.GZIPInputStream

internal class FakeClock(var now: Long = 1_700_000_000_000L, var elapsed: Long = 10_000L) : Clock {
    override fun currentTimeMillis() = now
    override fun elapsedRealtime() = elapsed
}

internal object SilentLogger : AnalyticsLogger {
    val warnings = mutableListOf<String>()
    override fun debug(message: String) = Unit
    override fun warn(message: String, error: Throwable?) {
        synchronized(warnings) { warnings += message }
    }
    override fun error(message: String, error: Throwable?) = Unit
}

/** Scriptable backend: responds with [responder], records every decoded request body. */
internal class FakeBackend(var responder: (JSONObject) -> HttpResponse = { HttpResponse(200) }) : NetworkClient {
    /** Handles GETs (remote config). */
    var configResponder: (HttpRequest) -> HttpResponse = { HttpResponse(404) }
    val configRequests = mutableListOf<HttpRequest>()
    val requests = mutableListOf<HttpRequest>()
    val bodies = mutableListOf<JSONObject>()

    val deliveredEvents: List<JSONObject>
        @Synchronized get() = bodies.zip(responses).filter { it.second in 200..299 }.flatMap { (body, _) ->
            val events = body.getJSONArray("events")
            (0 until events.length()).map { events.getJSONObject(it) }
        }

    private val responses = mutableListOf<Int>()

    @Synchronized
    override fun execute(request: HttpRequest): HttpResponse {
        if (request.method == "GET") {
            configRequests += request
            return configResponder(request)
        }
        val raw = if (request.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(request.body.inputStream()).readBytes()
        } else {
            request.body
        }
        val body = JSONObject(String(raw, Charsets.UTF_8))
        requests += request
        bodies += body
        // Keep bodies and responses aligned even when the responder throws (simulated offline).
        val response = try {
            responder(body)
        } catch (e: Exception) {
            responses += -1
            throw e
        }
        responses += response.code
        return response
    }

    companion object {
        fun offline(): (JSONObject) -> HttpResponse = { throw IOException("offline") }
    }
}
