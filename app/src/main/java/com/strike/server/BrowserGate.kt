package com.strike.server

import com.strike.core.Logs
import com.strike.online.BrowserAccess
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal const val ACCESS_PAGE = "/access"
internal const val ACCESS_API = "/api/access"
internal const val ACCESS_LOGIN = "/api/access/login"
internal const val ACCESS_COOKIE = "strike_browser"
private const val CAR_COOKIE = "strike_car"

class BrowserGate(val access: BrowserAccess) {
    private val carToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
        ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val browsers = HashSet<Socket>()

    fun nativeCookie(): String = "$CAR_COOKIE=$carToken; Path=/; HttpOnly; SameSite=Strict"

    fun isCar(peer: InetAddress, host: String?, cookie: String?, forwarded: Boolean): Boolean {
        if (forwarded || !peer.isLoopbackAddress || host != "127.0.0.1:${HttpServer.PORT}") return false
        val provided = cookieValue(cookie, CAR_COOKIE) ?: return false
        return MessageDigest.isEqual(carToken.toByteArray(Charsets.US_ASCII),
            provided.toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    fun enter(token: String?, socket: Socket): Boolean {
        if (!access.allows(token)) return false
        browsers.add(socket)
        return true
    }

    @Synchronized
    fun leave(socket: Socket) {
        browsers.remove(socket)
    }

    @Synchronized
    fun regenerate(): String {
        val code = access.regenerate()
        var failed = false
        for (socket in browsers) {
            try {
                socket.close()
            } catch (e: IOException) {
                failed = true
            }
        }
        browsers.clear()
        if (failed) Logs.w("Access", "A browser connection could not be closed")
        return code
    }

    fun guard(
        method: String,
        path: String,
        body: String,
        cookie: String?,
        secure: Boolean,
        socket: Socket,
        asset: (String) -> Response
    ): Response? {
        val token = cookieValue(cookie, ACCESS_COOKIE)
        if (path.startsWith(SECURITY_API)) return forbidden()
        if (path == ACCESS_API) {
            if (method != "GET") return methodNotAllowed()
            return Response(200, JSON, JSONObject().put("authenticated", access.allows(token))
                .toString().toByteArray())
        }
        if (path == ACCESS_LOGIN) {
            if (method != "POST") return methodNotAllowed()
            return login(body, secure)
        }
        if (method == "GET" && browserPublicAsset(path)) return asset(path)
        if (!enter(token, socket)) {
            if (method == "GET" && pagePath(path) != null) return asset(ACCESS_PAGE)
            return accessError(401, "Enter the access code")
        }
        if (method == "GET" && pagePath(path) == LOCK_PAGE) {
            return Response(302, TEXT, headers = mapOf("Location" to "/"))
        }
        return null
    }

    private fun login(body: String, secure: Boolean): Response {
        val code = try {
            formValue(body, "code") ?: ""
        } catch (e: IllegalArgumentException) {
            return accessError(400, "Enter the access code")
        }
        val attempt = access.login(code)
        if (attempt.unavailable) return accessError(503, "Set an access code in the car first")
        if (attempt.retryAfterSeconds > 0) {
            return accessError(429, "Too many tries. Wait before trying again", attempt.retryAfterSeconds)
        }
        val token = attempt.token ?: return accessError(401, "Wrong access code")
        return Response(200, JSON, JSONObject().put("ok", true).toString().toByteArray(),
            headers = mapOf("Set-Cookie" to browserCookie(token, secure)))
    }
}

internal fun browserCookie(token: String, secure: Boolean): String =
    "$ACCESS_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=${BrowserAccess.SESSION_SECONDS}" +
        if (secure) "; Secure" else ""

internal fun browserPublicAsset(path: String): Boolean = pagePath(path) == ACCESS_PAGE || when (path) {
    FAVICON_PATH, "/css/strike.css", "/css/tokens.css", "/js/access.js", "/js/session.js", "/img/wordmark.webp" -> true
    else -> false
}

private fun accessError(status: Int, message: String, retryAfterSeconds: Int = 0): Response =
    Response(status, JSON, JSONObject().put("ok", false).put("error", message)
        .put("retryAfterSeconds", retryAfterSeconds).toString().toByteArray(),
        headers = if (retryAfterSeconds > 0) mapOf("Retry-After" to retryAfterSeconds.toString())
        else emptyMap())
