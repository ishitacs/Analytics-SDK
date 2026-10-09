package com.example.analytics.network

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** [HttpURLConnection]-based client so the SDK doesn't force an OkHttp version on the host app. */
class DefaultNetworkClient(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 20_000,
) : NetworkClient {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            // doOutput would silently turn a GET into a POST, so only set it when there is a body.
            if (request.body.isNotEmpty() || request.method != "GET") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }

            val code = connection.responseCode
            // Read (bounded) and drain the body so the connection can go back to the keep-alive pool.
            val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { readBounded(it) }

            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
            return HttpResponse(code, headers, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() < MAX_BODY_BYTES) out.write(buffer, 0, minOf(read, MAX_BODY_BYTES - out.size()))
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_BODY_BYTES = 256 * 1024
    }
}
