package com.strike.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.URL
import java.util.Collections

private const val METRICS = "127.0.0.1:19889"

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
