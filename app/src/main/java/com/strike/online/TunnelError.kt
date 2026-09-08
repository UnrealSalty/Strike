package com.strike.online

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

private val OPAQUE_VALUE = Regex("[A-Za-z0-9_+/=-]{32,}")
private val CONTROL_CHAR = Regex("[\\p{Cntrl}]+")

internal fun tunnelError(line: String, token: String = ""): String? {
    val text = if (line.startsWith("{")) {
        try {
            val entry = JSONObject(line)
            if (entry.optString("level") !in setOf("error", "fatal", "panic")) return null
            entry.optString("error").ifEmpty { entry.optString("message") }
        } catch (e: JSONException) {
            line
        }
    } else line.trim()
    if (text.isEmpty() || text.contains("Initiating shutdown", true) ||
        text.contains("context canceled", true)) return null
    return when {
        text.contains("Unauthorized", true) || text.contains("token", true) ||
            text.contains("secret", true) -> "Cloudflare rejected the token. Update the setup"
        text.contains("x509:", true) -> "Cloudflare certificate check failed. Check the car's date and time"
        text.contains("no such host", true) || text.contains("DNS", true) ||
            text.contains("lookup", true) || text.contains("resolve SRV", true) ->
            "Cloudflare could not be resolved. Check the car's connection"
        text.contains("address already in use", true) -> "The tunnel's local port is already in use"
        text.contains("network is unreachable", true) -> "The tunnel cannot reach the internet"
        text.contains("i/o timeout", true) || text.contains("connection timed out", true) ->
            "The connection to Cloudflare timed out"
        text.contains("permission denied", true) -> "Android denied the tunnel access"
        else -> {
            val redacted = if (token.isEmpty()) text else {
                val credentials = JSONObject(String(Base64.getDecoder().decode(token), Charsets.UTF_8))
                text.replace(token, "[redacted]").replace(credentials.getString("s"), "[redacted]")
            }
            "Tunnel: " + CONTROL_CHAR.replace(OPAQUE_VALUE.replace(redacted, "[redacted]"), " ").take(240)
        }
    }
}
