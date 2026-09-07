package com.strike.online

import com.strike.core.Logs
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.IDN
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Locale

class TunnelSettings(private val file: File) {
    var enabled = false
        private set
    var mode = "off"
        private set
    var hostname = ""
        private set
    var token = ""
        private set

    init {
        try {
            if (file.isFile) {
                val saved = JSONObject(file.readText())
                mode = saved.optString("mode", "off")
                hostname = saved.optString("hostname")
                token = saved.optString("token")
                enabled = saved.optBoolean("enabled") && mode in TUNNEL_MODES &&
                    hostname.isNotEmpty() && validTunnelToken(token)
            }
        } catch (e: IOException) {
            Logs.w("Online", "Cannot read the tunnel settings; remote access is off")
        } catch (e: JSONException) {
            Logs.w("Online", "Tunnel settings are unreadable; remote access is off")
        }
    }

    fun configure(hostname: String, token: String, mode: String) {
        require(mode in TUNNEL_MODES) { "Choose when the tunnel should run" }
        val host = tunnelHostname(hostname)
        val secret = token.filterNot { it.isWhitespace() }.ifEmpty { this.token }
        require(validTunnelToken(secret)) { "Paste the tunnel token from Cloudflare" }
        save(false, mode, host, secret)
    }

    fun enable(enabled: Boolean) = save(enabled, mode, hostname, token)

    fun forget() = save(false, "off", "", "")

    private fun save(enabled: Boolean, mode: String, hostname: String, token: String) {
        val saved = JSONObject().put("enabled", enabled).put("mode", mode)
            .put("hostname", hostname).put("token", token)
        val parent = file.parentFile ?: throw IOException("Missing settings directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create settings directory")
        val pending = File(parent, file.name + ".tmp")
        pending.writeText(saved.toString())
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE)
        this.enabled = enabled
        this.mode = mode
        this.hostname = hostname
        this.token = token
    }
}

internal val TUNNEL_MODES = setOf("always", "off", "lock")

internal fun tunnelHostname(given: String): String {
    val value = given.trim().removeSuffix("/")
    val uri = try { URI(if (value.contains("://")) value else "https://$value") }
        catch (e: java.net.URISyntaxException) { throw IllegalArgumentException("Enter a hostname such as car.example.com") }
    require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 &&
        uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
        "Enter a hostname such as car.example.com"
    }
    val host = IDN.toASCII(uri.host ?: "").lowercase(Locale.US)
    require(host.length in 4..253 && host.contains('.') && host.split('.').all {
        it.length in 1..63 && it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))
    } && !host.all { it.isDigit() || it == '.' }) { "Enter a hostname such as car.example.com" }
    return host
}

internal fun validTunnelToken(token: String): Boolean {
    if (token.length !in 32..2048) return false
    return try {
        val decoded = Base64.getDecoder().decode(token)
        val payload = JSONObject(String(decoded, Charsets.UTF_8))
        payload.optString("a").isNotEmpty() && payload.optString("t").isNotEmpty() &&
            payload.optString("s").isNotEmpty()
    } catch (e: IllegalArgumentException) {
        false
    } catch (e: org.json.JSONException) {
        false
    }
}
