package com.strike.core

import java.security.SecureRandom

private const val TAG = "PinSession"
private const val TOKEN_BYTES = 16

// Each browser has its own session; ACC off and process death clear all sessions.
object PinSession {

    private val lock = Any()
    private val tokens = HashSet<String>()
    private val random = SecureRandom()

    fun allows(token: String?): Boolean = synchronized(lock) {
        token != null && tokens.contains(token)
    }

    fun unlock(): String = synchronized(lock) {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { b -> "%02x".format(b) }
        tokens.add(token)
        token
    }

    fun lock() = synchronized(lock) {
        if (tokens.isNotEmpty()) Logs.d(TAG, "session locked")
        tokens.clear()
    }
}
