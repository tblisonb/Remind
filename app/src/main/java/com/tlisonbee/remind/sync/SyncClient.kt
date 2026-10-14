package com.tlisonbee.remind.sync

import com.tlisonbee.remind.shared.SyncJson
import com.tlisonbee.remind.shared.SyncRequest
import com.tlisonbee.remind.shared.SyncResponse
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed class SyncException(message: String) : IOException(message) {
    class Unauthorized : SyncException("The server rejected the access token.")
    class Http(code: Int, body: String?) : SyncException("Server error $code${body?.let { ": ${it.take(200)}" }.orEmpty()}")
}

/** Blocking HTTP call to `POST /api/sync`; run it off the main thread. */
class SyncClient {
    fun sync(config: SyncConfig, request: SyncRequest): SyncResponse {
        val connection = URL(config.serverUrl.trimEnd('/') + "/api/sync").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use {
                it.write(SyncJson.encodeToString(SyncRequest.serializer(), request).toByteArray())
            }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    SyncJson.decodeFromString(SyncResponse.serializer(), body)
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> throw SyncException.Unauthorized()
                else -> throw SyncException.Http(code, connection.errorStream?.bufferedReader()?.use { it.readText() })
            }
        } finally {
            connection.disconnect()
        }
    }
}
