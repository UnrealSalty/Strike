package com.strike.server

internal const val LOCK_PAGE = "/lock"
internal const val SECURITY_API = "/api/security"
internal const val UNLOCK_API = "/api/security/unlock"
internal const val SESSION_COOKIE = "strike"

internal fun rewriteToLock(path: String, locked: Boolean): Boolean {
    if (!locked) return false
    val page = pagePath(path)
    return page != null && page != LOCK_PAGE
}

internal fun refuseWhenLocked(method: String, path: String, locked: Boolean): Boolean {
    if (!locked) return false
    if (pagePath(path) != null || path == FAVICON_PATH || path == "/img/wordmark.webp") return false
    if (path.startsWith("/css/") || path.startsWith("/js/")) return false
    if (path == SECURITY_API && method == "GET") return false
    if (path == UNLOCK_API && method == "POST") return false
    return true
}

internal fun cookieValue(header: String?, name: String): String? {
    if (header == null) return null
    for (part in header.split(';')) {
        val item = part.trim()
        val cut = item.indexOf('=')
        if (cut < 0) continue
        if (item.substring(0, cut) == name) {
            return item.substring(cut + 1).ifEmpty { null }
        }
    }
    return null
}

internal fun sessionCookie(token: String): String = "$SESSION_COOKIE=$token; Path=/; HttpOnly"

internal fun forbidden(): Response = Response(403, TEXT, "Locked".toByteArray())

internal fun sameOrigin(host: String?, origin: String?): Boolean {
    if (origin == null) return true
    return try {
        val parsed = java.net.URI(origin)
        parsed.scheme in setOf("http", "https") && parsed.rawUserInfo == null &&
            parsed.rawQuery == null && parsed.rawFragment == null && parsed.rawPath.isNullOrEmpty() &&
            host != null && host.equals(parsed.rawAuthority, ignoreCase = true)
    } catch (e: java.net.URISyntaxException) {
        false
    }
}
