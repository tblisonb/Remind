package com.tlisonbee.remind.desktop

import com.tlisonbee.remind.shared.SyncJson
import com.tlisonbee.remind.shared.SyncRequest
import com.tlisonbee.remind.shared.SyncResponse
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class SyncException(message: String) : IOException(message)

class SyncClient(private val config: DesktopConfig) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    fun sync(request: SyncRequest): SyncResponse {
        val httpRequest = HttpRequest.newBuilder(URI.create(config.serverUrl.trimEnd('/') + "/api/sync"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer ${config.token}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(SyncJson.encodeToString(SyncRequest.serializer(), request)))
            .build()
        val response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> SyncJson.decodeFromString(SyncResponse.serializer(), response.body())
            401 -> throw SyncException("The server rejected the access token; run `remind-desktop setup` again.")
            else -> throw SyncException("Server error ${response.statusCode()}: ${response.body().take(200)}")
        }
    }
}
