package com.strike.update

import org.json.JSONObject
import java.net.URI

internal const val RELEASES_API = "https://api.github.com/repos/UnrealSalty/Strike/releases/latest"
internal const val MAX_APK_BYTES = 150L * 1024 * 1024

internal data class Release(
    val version: String,
    val notes: String,
    val url: String,
    val bytes: Long,
    val sha256: String
) {
    fun json(): JSONObject = JSONObject().put("version", version).put("notes", notes)
        .put("url", url).put("bytes", bytes).put("sha256", sha256)
}

internal fun versionParts(version: String): List<Int>? {
    val name = version.removePrefix("v")
    if (!name.matches(Regex("[0-9]+(?:\\.[0-9]+){0,2}"))) return null
    val parts = name.split('.').map { it.toIntOrNull() ?: return null }
    return parts + List(3 - parts.size) { 0 }
}

internal fun compareVersions(left: String, right: String): Int {
    val a = versionParts(left) ?: throw IllegalArgumentException("Unrecognised release version")
    val b = versionParts(right) ?: throw IllegalArgumentException("Unrecognised installed version")
    for (i in a.indices) if (a[i] != b[i]) return a[i].compareTo(b[i])
    return 0
}

internal fun releaseFrom(payload: JSONObject): Release? {
    if (payload.optBoolean("draft") || payload.optBoolean("prerelease")) return null
    val assets = payload.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
        .singleOrNull { it.optString("name") == "Strike.apk" && it.optString("state") == "uploaded" }
        ?: return null
    return checkedRelease(payload.getString("tag_name"), payload.optString("body").take(16_384),
        apk.getString("browser_download_url"), apk.getLong("size"),
        apk.optString("digest").removePrefix("sha256:"))
}

internal fun savedRelease(payload: JSONObject): Release = checkedRelease(
    payload.getString("version"), payload.getString("notes").take(16_384),
    payload.getString("url"), payload.getLong("bytes"), payload.getString("sha256"))

private fun checkedRelease(version: String, notes: String, url: String, bytes: Long, sha256: String): Release {
    require(versionParts(version) != null) { "Use a release tag such as v0.2" }
    val uri = URI(url)
    require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath == "/UnrealSalty/Strike/releases/download/$version/Strike.apk") {
        "The release APK link is invalid"
    }
    require(bytes in 1..MAX_APK_BYTES) { "The release APK size is invalid" }
    require(sha256.matches(Regex("[0-9a-f]{64}"))) { "The release APK has no SHA-256 checksum" }
    return Release(version, notes, url, bytes, sha256)
}
