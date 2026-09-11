package com.strike.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.Collections
import java.util.Locale
import org.json.JSONObject

private const val METRICS = "127.0.0.1:19889"

internal class CloudflareMethod(
    private val context: Context,
    private val settings: OnlineSettings
) : RemoteMethod {
    override val connecting = "Connecting to Cloudflare"
    override val problem: String? = null

    override fun prepare() = Unit

    override fun start(): Process = cloudflared(context, settings.secret(CLOUDFLARE))

    override fun ready(): Boolean = tunnelReady()

    override fun failure(line: String): String? = tunnelError(line, settings.secret(CLOUDFLARE))

    override fun address(): String? =
        settings.name(CLOUDFLARE).ifEmpty { null }?.let { "https://$it/" }
}

internal fun cloudflared(context: Context, token: String): Process {
    val binary = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
    val builder = ProcessBuilder(binary.absolutePath, "tunnel", "--no-autoupdate",
        "--protocol", "http2", "--edge-ip-version", "4", "--metrics", METRICS,
        "--loglevel", "error", "--output", "json", "--grace-period", "2s", "run")
    builder.environment()["TUNNEL_TOKEN"] = token
    builder.environment()["STRIKE_PARENT_PIPE"] = "1"
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = connectivity.activeNetwork
    val link = active?.let { connectivity.getLinkProperties(it) }
    if (link != null) {
        builder.environment()["STRIKE_DNS_SERVERS"] = link.dnsServers.mapNotNull { it.hostAddress }.joinToString(" ")
        if (link.isPrivateDnsActive) {
            builder.environment()["STRIKE_DNS_TLS"] = "1"
            builder.environment()["STRIKE_DNS_NAME"] = link.privateDnsServerName.orEmpty()
        }
    }
    builder.directory(context.filesDir)
    builder.redirectErrorStream(true)
    return builder.start()
}

internal fun tunnelReady(): Boolean {
    val connection = URL("http://$METRICS/ready").openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 500
        connection.readTimeout = 500
        connection.instanceFollowRedirects = false
        connection.responseCode == 200
    } catch (e: IOException) {
        false
    } finally {
        connection.disconnect()
    }
}

internal fun tunnelNetwork(context: Context): String? {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = internetNetwork(connectivity) ?: return null
    val link = connectivity.getLinkProperties(active)
    return "$active:${link?.dnsServers}:${link?.isPrivateDnsActive}:${link?.privateDnsServerName}"
}

internal fun hasInternet(context: Context): Boolean =
    internetNetwork(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager) != null

private fun internetNetwork(connectivity: ConnectivityManager): Network? {
    val active = connectivity.activeNetwork ?: return null
    return active.takeIf { connectivity.getNetworkCapabilities(it)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
}

internal fun localAddresses(): List<String> = try {
    val interfaces = NetworkInterface.getNetworkInterfaces()
    if (interfaces == null) emptyList() else Collections.list(interfaces).filter {
        it.isUp && !it.isLoopback
    }.flatMap { Collections.list(it.inetAddresses) }.filter {
        it is Inet4Address && it.isSiteLocalAddress && !it.isLinkLocalAddress
    }.mapNotNull { it.hostAddress }.distinct().sorted()
} catch (e: java.net.SocketException) {
    emptyList()
}

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
