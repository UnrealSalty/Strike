package com.strike.online

import com.strike.core.Logs
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val CLOUDFLARE = "cloudflare"
internal const val TAILSCALE = "tailscale"
internal const val ZROK = "zrok"

internal val ONLINE_METHODS = listOf(CLOUDFLARE, TAILSCALE, ZROK)
internal val TUNNEL_MODES = setOf("always", "off", "lock")

class OnlineSettings(private val file: File) {
    private val names = HashMap<String, String>()
    private val secrets = HashMap<String, String>()

    var enabled = false
        private set
    var mode = "off"
        private set
    var method = CLOUDFLARE
        private set

    init {
        try {
            if (file.isFile) {
                val saved = JSONObject(file.readText())
                mode = saved.optString("mode", "off")
                method = saved.optString("method", CLOUDFLARE).takeIf { it in ONLINE_METHODS } ?: CLOUDFLARE
                for (service in ONLINE_METHODS) {
                    val entry = saved.optJSONObject(service) ?: continue
                    names[service] = entry.optString("name")
                    secrets[service] = entry.optString("secret")
                }
                // Settings saved before Strike offered a choice of service held Cloudflare at the top level.
                if (!saved.has("method") && saved.has("token")) {
                    names[CLOUDFLARE] = saved.optString("hostname")
                    secrets[CLOUDFLARE] = saved.optString("token")
                }
                enabled = saved.optBoolean("enabled") && mode in TUNNEL_MODES && isConfigured(method)
            }
        } catch (e: IOException) {
            Logs.w("Online", "Cannot read the remote access settings; remote access is off")
        } catch (e: JSONException) {
            Logs.w("Online", "Remote access settings are unreadable; remote access is off")
        }
    }

    val configured: Boolean get() = isConfigured(method)

    fun name(service: String): String = names[service].orEmpty()

    fun secret(service: String): String = secrets[service].orEmpty()

    fun isConfigured(service: String): Boolean = when (service) {
        CLOUDFLARE -> name(service).isNotEmpty() && validTunnelToken(secret(service))
        TAILSCALE -> validTailscaleKey(secret(service))
        ZROK -> name(service).isNotEmpty() && secret(service).isNotEmpty()
        else -> false
    }

    fun select(service: String) {
        require(service in ONLINE_METHODS) { "Choose a remote access service" }
        method = service
        save()
    }

    fun configure(service: String, name: String, secret: String, mode: String) {
        require(service in ONLINE_METHODS) { "Choose a remote access service" }
        require(mode in TUNNEL_MODES) { "Choose when remote access should run" }
        val kept = secret.filterNot { it.isWhitespace() }.ifEmpty { secret(service) }
        val checked = when (service) {
            CLOUDFLARE -> {
                require(validTunnelToken(kept)) { "Paste the tunnel token from Cloudflare" }
                tunnelHostname(name)
            }
            TAILSCALE -> {
                require(validTailscaleKey(kept)) { "Paste an auth key from Tailscale" }
                ""
            }
            else -> {
                require(kept.isNotEmpty()) { "Paste the account token from zrok" }
                zrokShareName(name)
            }
        }
        method = service
        this.mode = mode
        names[service] = checked
        secrets[service] = kept
        enabled = false
        save()
    }

    fun enable(enabled: Boolean) {
        this.enabled = enabled
        save()
    }

    fun forget() {
        names.remove(method)
        secrets.remove(method)
        enabled = false
        save()
    }

    private fun save() {
        val saved = JSONObject().put("enabled", enabled).put("mode", mode).put("method", method)
        for (service in ONLINE_METHODS) {
            if (name(service).isEmpty() && secret(service).isEmpty()) continue
            saved.put(service, JSONObject().put("name", name(service)).put("secret", secret(service)))
        }
        val parent = file.parentFile ?: throw IOException("Missing settings directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create settings directory")
        val pending = File(parent, file.name + ".tmp")
        try {
            FileOutputStream(pending).use {
                it.write(saved.toString().toByteArray())
                it.fd.sync()
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (pending.isFile) pending.delete()
        }
    }
}
