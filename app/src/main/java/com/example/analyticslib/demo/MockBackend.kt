package com.example.analyticslib.demo

import android.os.Process
import android.util.Log
import com.example.analytics.network.HttpRequest
import com.example.analytics.network.HttpResponse
import com.example.analytics.network.NetworkClient
import org.json.JSONObject
import java.io.IOException
import java.util.zip.GZIPInputStream

enum class BackendMode(val label: String) {
    HEALTHY("Healthy"),
    OFFLINE("Offline"),
    SERVER_ERROR("503 + Retry-After"),
    TOO_LARGE("413 if > 3 events"),
}

/** What the mock serves at the remote-config endpoint. */
enum class RemoteMode(val label: String, val json: String) {
    NORMAL("Normal", "{}"),
    PAUSE_UPLOADS("Pause uploads", """{"uploads_enabled":false}"""),
    BLOCK_IMPRESSIONS("Block impressions", """{"blocked_events":["product_*_impression","product_impression"]}"""),
    SAMPLED_OUT("Sample 0%", """{"sample_rate":0}"""),
}

/**
 * In-process stand-in for a real ingestion API, so the demo needs no server. Switch [mode] from
 * the debug panel to exercise the SDK's offline, retry and batch-splitting paths, and
 * [remoteMode] to change what the remote-config endpoint (any GET) returns.
 */
object MockBackend : NetworkClient {

    @Volatile
    var mode: BackendMode = BackendMode.HEALTHY

    @Volatile
    var remoteMode: RemoteMode = RemoteMode.NORMAL

    override fun execute(request: HttpRequest): HttpResponse {
        Thread.sleep(300) // simulated latency
        if (request.method == "GET") return serveRemoteConfig(request)
        val body = decode(request)
        val events = body.getJSONArray("events")
        val parsed = (0 until events.length()).map { events.getJSONObject(it) }
        val names = parsed.map { it.getString("name") }
        // e.g. "impression×6, click×1": event names alone are ambiguous in a one-line log.
        val summary = parsed.groupingBy { it.getString("type") }.eachCount()
            .entries.joinToString { "${it.key}×${it.value}" }
        val code = when (mode) {
            BackendMode.HEALTHY -> 200
            BackendMode.OFFLINE -> {
                DebugState.log("✗ offline: ${names.size} events kept on disk")
                throw IOException("Simulated: no connectivity")
            }
            BackendMode.SERVER_ERROR -> 503
            BackendMode.TOO_LARGE -> if (names.size > 3) 413 else 200
        }
        Log.i("MockBackend", "[pid ${Process.myPid()}] HTTP $code for ${names.size} events")
        parsed.forEach { Log.d("MockBackend", "  " + describe(it)) }
        DebugState.log(if (code == 200) "✓ 200: $summary" else "✗ $code for ${names.size} events")
        return if (code == 503) HttpResponse(code, mapOf("Retry-After" to listOf("10"))) else HttpResponse(code)
    }

    private fun serveRemoteConfig(request: HttpRequest): HttpResponse {
        val mode = remoteMode
        val etag = "\"${mode.name}\""
        if (request.headers["If-None-Match"] == etag) {
            DebugState.log("config 304 (unchanged: ${mode.label})")
            return HttpResponse(304)
        }
        DebugState.log("config 200 → ${mode.label}")
        return HttpResponse(200, mapOf("ETag" to listOf(etag)), mode.json.toByteArray())
    }

    /** One readable line per event for logcat: type, name, item, session/user and link ids. */
    private fun describe(event: JSONObject): String {
        val props = event.getJSONObject("properties")
        fun short(value: String?) = value?.take(8) ?: "-"
        return buildString {
            append(event.getString("type")).append(' ').append(event.getString("name"))
            props.optString("item_key").takeIf { it.isNotEmpty() }?.let { append(" item=").append(it) }
            append(" session=").append(short(event.optString("session_id").ifEmpty { null }))
            append(" user=").append(event.optString("user_id").ifEmpty { "-" }.takeUnless { it == "null" } ?: "-")
            props.optString("screen_visit_id").takeIf { it.isNotEmpty() }?.let { append(" visit=").append(short(it)) }
            props.optString("impression_id").takeIf { it.isNotEmpty() }?.let { append(" imp=").append(short(it)) }
            props.optString("reason").takeIf { it.isNotEmpty() }?.let { append(" reason=").append(it) }
        }
    }

    private fun decode(request: HttpRequest): JSONObject {
        val bytes = if (request.headers["Content-Encoding"] == "gzip") {
            GZIPInputStream(request.body.inputStream()).use { it.readBytes() }
        } else {
            request.body
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }
}
