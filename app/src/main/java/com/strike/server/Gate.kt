package com.strike.server

internal const val LOCK_PAGE = "/lock.html"
internal const val SECURITY_API = "/api/security"
internal const val UNLOCK_API = "/api/security/unlock"
internal const val SESSION_COOKIE = "strike"

internal fun rewriteToLock(path: String, locked: Boolean): Boolean {
    if (!locked) return false
    if (path == LOCK_PAGE) return false
    return path == "/" || path.endsWith(".html")
}

internal fun refuseWhenLocked(method: String, path: String, locked: Boolean): Boolean {
    if (!locked) return false
    if (path == LOCK_PAGE) return false
    if (path.startsWith("/css/") || path.startsWith("/js/")) return false
    if (path == SECURITY_API && method == "GET") return false
    if (path == UNLOCK_API && method == "POST") return false
    if (path == "/" || path.endsWith(".html")) return false
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
