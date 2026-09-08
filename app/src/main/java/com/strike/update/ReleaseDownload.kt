package com.strike.update

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal data class ReleaseReply(val release: Release?, val etag: String)

internal fun fetchRelease(etag: String, previous: Release?): ReleaseReply {
    val connection = connect(RELEASES_API)
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
    if (etag.isNotEmpty()) connection.setRequestProperty("If-None-Match", etag)
    try {
        return when (connection.responseCode) {
            200 -> ReleaseReply(connection.inputStream.use {
                releaseFrom(JSONObject(String(readLimited(it, 262_144), Charsets.UTF_8)))
            }, connection.getHeaderField("ETag") ?: "")
            304 -> ReleaseReply(previous, etag)
            404 -> ReleaseReply(null, "")
            403, 429 -> throw IOException("GitHub limited update checks. Try again later")
            else -> throw IOException("GitHub could not provide the release")
        }
    } finally {
        connection.disconnect()
    }
}

internal fun downloadRelease(release: Release, file: File, progress: (Long) -> Unit) {
    var url = release.url
    repeat(5) {
        val connection = connect(url)
        try {
            when (connection.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val next = URL(URL(url), connection.getHeaderField("Location")
                        ?: throw IOException("The download link is unavailable"))
                    if (!downloadHost(next)) throw IOException("The download left GitHub")
                    url = next.toString()
                }
                200 -> {
                    val length = connection.contentLengthLong
                    if (length >= 0 && length != release.bytes) throw IOException("The APK size changed. Check again")
                    connection.inputStream.use { writeDownload(it, file, release, progress) }
                    return
                }
                else -> throw IOException("The APK could not be downloaded")
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("The download redirected too many times")
}

internal fun writeDownload(input: InputStream, file: File, release: Release, progress: (Long) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    var received = 0L
    file.outputStream().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            received += count
            if (received > release.bytes) throw IOException("The APK is larger than expected")
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            progress(received)
        }
        output.fd.sync()
    }
    if (received != release.bytes || hex(digest.digest()) != release.sha256) {
        throw IOException("The APK download is incomplete or damaged. Download it again")
    }
}

internal fun fileDigest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

private fun downloadHost(url: URL): Boolean = url.protocol == "https" && url.port == -1 &&
    url.userInfo == null && url.host in setOf("github.com", "release-assets.githubusercontent.com",
        "objects.githubusercontent.com", "github-releases.githubusercontent.com")

private fun connect(url: String): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
    connectTimeout = 10_000
    readTimeout = 15_000
    instanceFollowRedirects = false
    setRequestProperty("User-Agent", "Strike")
}

private fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > limit) throw IOException("GitHub returned too much release information")
        output.write(buffer, 0, count)
    }
}
