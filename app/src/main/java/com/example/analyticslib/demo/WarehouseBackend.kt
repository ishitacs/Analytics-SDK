package com.example.analyticslib.demo

import android.util.Log
import com.example.analytics.network.HttpRequest
import com.example.analytics.network.HttpResponse
import com.example.analytics.network.NetworkClient
import org.json.JSONObject
import java.util.zip.GZIPInputStream

/** Mock for a second destination (e.g. a data-warehouse collector). Always healthy. */
object WarehouseBackend : NetworkClient {
    override fun execute(request: HttpRequest): HttpResponse {
        val bytes = GZIPInputStream(request.body.inputStream()).use { it.readBytes() }
        val events = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("events")
        val types = (0 until events.length()).map { events.getJSONObject(it).getString("type") }
        val summary = types.groupingBy { it }.eachCount().entries.joinToString { "${it.key}×${it.value}" }
        Log.i("WarehouseBackend", "HTTP 200 for ${types.size} events: $summary")
        DebugState.log("warehouse ✓ $summary")
        return HttpResponse(200)
    }
}
