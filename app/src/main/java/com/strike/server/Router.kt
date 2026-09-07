package com.strike.server

import android.content.Context
import com.strike.camera.CameraView
import com.strike.core.Pin
import com.strike.core.PinSession
import com.strike.daemon.DaemonClient
import com.strike.daemon.Shell
import com.strike.server.api.DaemonsApi
import com.strike.server.api.DashboardApi
import com.strike.server.api.RECORDER_API
import com.strike.server.api.RecordingsApi
import com.strike.server.api.SecurityApi
import com.strike.server.api.SurveillanceApi
import java.io.File
import java.io.IOException

private const val WEB_ROOT = "web"
private const val CLIP_API = "/api/recording/clips/"
private const val EVENT_API = "/api/surveillance/events/"
private const val BYD_API = "/api/byd/"

internal const val CLIPS_PATH = "/clips/"
internal const val THUMBS_PATH = "/thumbs/"
internal const val EVENTS_PATH = "/events/"
internal const val HEROES_PATH = "/heroes/"
internal const val LIVE_STREAM_PATH = "/live/stream"

class Router(context: Context, private val pin: Pin) {

    private val assets = context.assets
    private val shell = Shell(context)
    private val recordings = RecordingsApi(context, shell)
    private val surveillance = SurveillanceApi(context, shell)
    private val daemons = DaemonsApi(context, shell)
    private val dashboard = DashboardApi(context, shell, daemons, pin)
    private val security = SecurityApi(pin)
    private val live = LiveStream(DaemonClient())

    fun locked(token: String?): Boolean = pin.isSet() && !PinSession.allows(token)

    fun stream(socket: WebSocket, view: String?) = live.serve(socket, CameraView.of(view).id)

    fun asset(path: String): Response {
        val assetPath = resolveAssetPath(path) ?: return notFound()
        val body = try {
            assets.open(assetPath).use { it.readBytes() }
        } catch (e: IOException) {
            return notFound()
        }
        return Response(200, contentTypeFor(assetPath), body)
    }

    fun clip(name: String, range: String?): Response = recordings.clip(name, range)

    fun thumb(name: String): Response = recordings.thumb(name)

    fun event(name: String, range: String?): Response = surveillance.clip(name, range)

    fun hero(name: String): Response = surveillance.hero(name)

    fun api(method: String, path: String, body: String): Response = when {
        path == SECURITY_API -> when (method) {
            "GET" -> security.status()
            "POST" -> security.update(body)
            else -> methodNotAllowed()
        }
        path == UNLOCK_API -> if (method == "POST") security.unlock(body) else methodNotAllowed()
        path == "/api/status" -> if (method == "GET") dashboard.status() else methodNotAllowed()
        path == "/api/recording/settings" -> when (method) {
            "GET" -> recordings.settings()
            "POST" -> recordings.save(body)
            else -> methodNotAllowed()
        }
        path == "/api/recording/clips" -> if (method == "GET") recordings.clips() else methodNotAllowed()
        path.startsWith(CLIP_API) -> {
            if (method == "DELETE") recordings.delete(path.substring(CLIP_API.length))
            else methodNotAllowed()
        }
        path == "/api/surveillance/settings" -> when (method) {
            "GET" -> surveillance.settings()
            "POST" -> surveillance.save(body)
            else -> methodNotAllowed()
        }
        path == "/api/surveillance/events" -> if (method == "GET") surveillance.events() else methodNotAllowed()
        path == "/api/surveillance/preview" -> if (method == "POST") surveillance.preview() else methodNotAllowed()
        path.startsWith(EVENT_API) -> {
            if (method == "DELETE") surveillance.delete(path.substring(EVENT_API.length))
            else methodNotAllowed()
        }
        path == "/api/live/cameras" -> if (method == "GET") daemons.cameras() else methodNotAllowed()
        path == "/api/daemons" -> if (method == "GET") daemons.daemons() else methodNotAllowed()
        path == "/api/daemons/shell" -> if (method == "POST") daemons.connect() else methodNotAllowed()
        path == RECORDER_API -> if (method == "POST") daemons.setRecorder(body) else methodNotAllowed()
        path == "/api/logs" -> if (method == "GET") daemons.logs() else methodNotAllowed()
        path.startsWith(BYD_API) -> {
            if (method == "POST") daemons.setByd(path.substring(BYD_API.length), body)
            else methodNotAllowed()
        }
        else -> notFound()
    }
}

class Response(
    val status: Int,
    val contentType: String,
    val body: ByteArray = ByteArray(0),
    val slice: FileSlice? = null,
    val headers: Map<String, String> = emptyMap()
)

class FileSlice(
    val file: File,
    val offset: Long,
    val length: Long,
    val totalBytes: Long
)

internal const val TEXT = "text/plain; charset=utf-8"
internal const val JSON = "application/json"

internal fun notFound(): Response = Response(404, TEXT, "Not found".toByteArray())

internal fun methodNotAllowed(): Response = Response(405, TEXT, "Method not allowed".toByteArray())

internal fun resolveAssetPath(path: String): String? {
    if (!path.startsWith("/")) return null
    if (path.contains("..") || path.contains('\\') || path.contains("//")) return null
    if (path == "/") return "$WEB_ROOT/index.html"
    return WEB_ROOT + path
}

internal fun contentTypeFor(path: String): String = when {
    path.endsWith(".html") -> "text/html; charset=utf-8"
    path.endsWith(".css") -> "text/css; charset=utf-8"
    path.endsWith(".js") -> "application/javascript; charset=utf-8"
    path.endsWith(".json") -> JSON
    else -> "application/octet-stream"
}
