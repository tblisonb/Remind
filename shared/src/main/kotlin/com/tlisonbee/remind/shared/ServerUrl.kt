package com.tlisonbee.remind.shared

import java.net.URI

/**
 * Returns the server URL without a trailing slash, or null if it isn't usable. HTTPS is required;
 * plain HTTP is accepted only for localhost (a dev server, e.g. forwarded with `adb reverse`).
 */
fun normalizeServerUrl(input: String): String? {
    val uri = try {
        URI(input.trim().trimEnd('/'))
    } catch (_: Exception) {
        return null
    }
    val host = uri.host ?: return null
    val ok = when (uri.scheme) {
        "https" -> true
        "http" -> host == "localhost" || host == "127.0.0.1"
        else -> false
    }
    return if (ok && uri.query == null && uri.fragment == null) uri.toString() else null
}
