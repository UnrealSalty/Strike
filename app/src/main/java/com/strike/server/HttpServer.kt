package com.strike.server

import com.strike.core.Logs
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private const val TAG = "HttpServer"
private const val READ_TIMEOUT_MS = 15_000
private const val CONTENT_LENGTH = "Content-Length:"
private const val RANGE = "Range:"
private const val SOCKET_KEY = "Sec-WebSocket-Key:"
private const val COOKIE = "Cookie:"
private const val BODY_MAX = 4096
private const val COPY_BUFFER = 64 * 1024

class HttpServer(private val port: Int, private val router: Router, private val browsers: BrowserGate) {

    private val workers = ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16))

    fun start() {
        val socket = try {
            ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))
        } catch (e: IOException) {
            Logs.e(TAG, "port $port is taken, the UI has nothing to load", e)
            return
        }
        socket.reuseAddress = true
        Thread({ accept(socket) }, "strike-http").start()
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                Logs.e(TAG, "stopped accepting connections", e)
                return
            }
            try {
                workers.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                try { client.close() } catch (e: IOException) {
                    // The rejected client may already have disconnected.
                }
            }
        }
    }

    private fun serve(client: Socket) {
        var streaming = false
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
            val requestLine = boundedLine(reader) ?: return
            var bodyLength = 0
            var range: String? = null
            var socketKey: String? = null
            var cookie: String? = null
            var host: String? = null
            var origin: String? = null
            var forwarded = false
            var secure = false
            var headerBytes = 0
            var hasLength = false
            while (true) {
                val header = boundedLine(reader) ?: return
                if (header.isEmpty()) break
                headerBytes += header.length + 2
                if (headerBytes > 32_768 || !header.contains(':')) throw IOException("Invalid headers")
                if (header.startsWith(CONTENT_LENGTH, ignoreCase = true)) {
                    if (hasLength) throw IOException("Duplicate body length")
                    hasLength = true
                    bodyLength = header.substringAfter(':').trim().toIntOrNull()
                        ?: throw IOException("Invalid body length")
                    if (bodyLength !in 0..BODY_MAX) throw IOException("Body too large")
                }
                if (header.startsWith("Transfer-Encoding:", ignoreCase = true)) throw IOException("Unsupported encoding")
                if (header.startsWith(RANGE, ignoreCase = true)) {
                    range = header.substringAfter(':').trim()
                }
                if (header.startsWith(SOCKET_KEY, ignoreCase = true)) {
                    socketKey = header.substringAfter(':').trim()
                }
                if (header.startsWith(COOKIE, ignoreCase = true)) {
                    if (cookie != null) throw IOException("Duplicate cookie header")
                    cookie = header.substringAfter(':').trim()
                }
                if (header.startsWith("Host:", ignoreCase = true)) {
                    if (host != null) throw IOException("Duplicate host")
                    host = header.substringAfter(':').trim()
                }
                if (header.startsWith("Origin:", ignoreCase = true)) {
                    if (origin != null) throw IOException("Duplicate origin")
                    origin = header.substringAfter(':').trim()
                }
                if (header.startsWith("Cf-Connecting-Ip:", ignoreCase = true)) forwarded = true
                if (header.startsWith("X-Forwarded-Proto:", ignoreCase = true)) {
                    secure = header.substringAfter(':').trim().equals("https", ignoreCase = true)
                }
            }
            if (host == null) throw IOException("Missing host")
            val method = requestMethod(requestLine) ?: return
            val path = requestPath(requestLine) ?: return
            if ((socketKey != null || method != "GET") && !sameOrigin(host, origin)) {
                write(client.getOutputStream(), forbidden())
                return
            }
            val body = read(reader, bodyLength)
            val inCar = browsers.isCar(client.inetAddress, host, cookie, forwarded)
            if (!inCar) {
                val gate = browsers.guard(method, path, body, cookie, secure || forwarded, client, router::asset)
                if (gate != null) {
                    write(client.getOutputStream(), gate)
                    return
                }
            }
            val token = cookieValue(cookie, SESSION_COOKIE)
            if (path == LIVE_STREAM_PATH) {
                if (inCar && refuseWhenLocked("GET", LIVE_STREAM_PATH, router.locked(token))) {
                    write(client.getOutputStream(), forbidden())
                    return
                }
                streaming = upgrade(client, socketKey, queryValue(requestLine, "view"))
                if (streaming) return
            }
            write(client.getOutputStream(), respond(requestLine, body, range, token, inCar))
        } catch (e: IOException) {
            // The WebView drops connections on navigation; logging here floods.
        } finally {
            // The Live thread owns this socket after upgrade.
            if (!streaming) {
                browsers.leave(client)
                try {
                    client.close()
                } catch (e: IOException) {
                    // Nothing left to do with a socket we are done with.
                }
            }
        }
    }

    // Long-lived viewers use separate threads so API workers remain available.
    private fun upgrade(client: Socket, socketKey: String?, view: String?): Boolean {
        val accept = acceptKey(socketKey) ?: return false
        client.soTimeout = 0
        client.tcpNoDelay = true
        val output = client.getOutputStream()
        output.write(upgradeResponse(accept))
        output.flush()
        Thread({
            try {
                router.stream(WebSocket(client.getInputStream(), output), view)
            } catch (e: IOException) {
                Logs.d(TAG, "the live viewer's connection ended: ${e.message}")
            } finally {
                browsers.leave(client)
                try {
                    client.close()
                } catch (e: IOException) {
                    // The viewer already went away.
                }
            }
        }, "strike-live").start()
        return true
    }

    private fun read(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val chars = CharArray(minOf(length, BODY_MAX))
        var filled = 0
        while (filled < chars.size) {
            val count = reader.read(chars, filled, chars.size - filled)
            if (count < 0) throw IOException("Incomplete request body")
            filled += count
        }
        return String(chars, 0, filled)
    }

    private fun respond(requestLine: String, body: String, range: String?, token: String?, inCar: Boolean): Response {
        val method = requestMethod(requestLine)
        val path = requestPath(requestLine)
        if (method == null || path == null) {
            return Response(400, TEXT, "Bad request".toByteArray())
        }
        return try {
            route(method, path, body, range, token, inCar)
        } catch (e: RuntimeException) {
            Logs.e(TAG, "$method $path failed", e)
            Response(500, TEXT, "Strike could not answer that".toByteArray())
        }
    }

    private fun route(
        method: String,
        path: String,
        body: String,
        range: String?,
        token: String?,
        inCar: Boolean
    ): Response {
        val locked = inCar && router.locked(token)
        if (rewriteToLock(path, locked)) return router.asset(LOCK_PAGE)
        if (refuseWhenLocked(method, path, locked)) return forbidden()
        return when {
            path.startsWith("/api/") -> router.api(method, path, body, inCar)
            method != "GET" -> methodNotAllowed()
            path.startsWith(CLIPS_PATH) -> router.clip(path.substring(CLIPS_PATH.length), range)
            path.startsWith(THUMBS_PATH) -> router.thumb(path.substring(THUMBS_PATH.length))
            path.startsWith(EVENTS_PATH) -> router.event(path.substring(EVENTS_PATH.length), range)
            path.startsWith(HEROES_PATH) -> router.hero(path.substring(HEROES_PATH.length))
            else -> router.asset(path)
        }
    }

    private fun write(out: OutputStream, response: Response) {
        out.write(headerBlock(response).toByteArray())
        val slice = response.slice
        if (slice == null) out.write(response.body) else stream(out, slice)
        out.flush()
    }

    private fun stream(out: OutputStream, slice: FileSlice) {
        RandomAccessFile(slice.file, "r").use { file ->
            file.seek(slice.offset)
            val buffer = ByteArray(COPY_BUFFER)
            var left = slice.length
            while (left > 0) {
                val count = file.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (count < 0) break
                out.write(buffer, 0, count)
                left -= count
            }
        }
    }

    companion object {
        const val PORT = 8090
    }
}

internal fun boundedLine(reader: BufferedReader): String? {
    val line = StringBuilder()
    while (line.length <= 8192) {
        val next = reader.read()
        if (next < 0) return if (line.isEmpty()) null else throw IOException("Incomplete header")
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(next.toChar())
    }
    throw IOException("Header too long")
}

internal fun requestMethod(requestLine: String): String? =
    requestLine.substringBefore(' ', "").ifEmpty { null }

internal fun requestPath(requestLine: String): String? {
    val target = requestLine.split(' ').getOrNull(1) ?: return null
    val path = target.substringBefore('?').substringBefore('#')
    return if (path.startsWith("/")) path else null
}

internal fun queryValue(requestLine: String, name: String): String? {
    val target = requestLine.split(' ').getOrNull(1) ?: return null
    if (!target.contains('?')) return null
    for (pair in target.substringAfter('?').substringBefore('#').split('&')) {
        if (pair.substringBefore('=') == name) {
            return pair.substringAfter('=', "").ifEmpty { null }
        }
    }
    return null
}

internal fun headerBlock(response: Response): String {
    val slice = response.slice
    val headers = StringBuilder()
        .append("HTTP/1.1 ").append(response.status).append("\r\n")
        .append("Content-Type: ").append(response.contentType).append("\r\n")
        .append("Content-Length: ").append(slice?.length ?: response.body.size.toLong()).append("\r\n")
    if (slice != null) {
        headers.append("Accept-Ranges: bytes\r\n")
        if (response.status == 206) {
            headers.append("Content-Range: bytes ")
                .append(slice.offset).append('-').append(slice.offset + slice.length - 1)
                .append('/').append(slice.totalBytes).append("\r\n")
        }
    }
    for ((name, value) in response.headers) {
        headers.append(name).append(": ").append(value).append("\r\n")
    }
    return headers.append("Cache-Control: no-store\r\n")
        .append("X-Frame-Options: DENY\r\n")
        .append("Referrer-Policy: no-referrer\r\n")
        .append("Connection: close\r\n\r\n").toString()
}

// Null selects a full response; multipart ranges are not supported.
internal fun rangeOf(header: String?, totalBytes: Long): LongRange? {
    if (header == null || totalBytes <= 0) return null
    val spec = header.substringAfter("bytes=", "").trim()
    if (spec.isEmpty() || spec.contains(',')) return null
    val dash = spec.indexOf('-')
    if (dash < 0) return null
    val fromText = spec.substring(0, dash)
    val toText = spec.substring(dash + 1)
    if (fromText.isEmpty()) {
        val lastBytes = toText.toLongOrNull() ?: return null
        if (lastBytes <= 0) return null
        return maxOf(0L, totalBytes - lastBytes)..totalBytes - 1
    }
    val from = fromText.toLongOrNull() ?: return null
    if (from < 0 || from >= totalBytes) return null
    val to = if (toText.isEmpty()) totalBytes - 1 else toText.toLongOrNull() ?: return null
    if (to < from) return null
    return from..minOf(to, totalBytes - 1)
}

internal fun formValue(body: String, name: String): String? {
    for (pair in body.split('&')) {
        val separator = pair.indexOf('=')
        if (separator < 0) continue
        if (URLDecoder.decode(pair.substring(0, separator), "UTF-8") != name) continue
        return URLDecoder.decode(pair.substring(separator + 1), "UTF-8")
    }
    return null
}
