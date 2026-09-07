package com.strike.core

import android.util.Log

private const val KEPT = 200

class LogLine(val atMs: Long, val level: String, val tag: String, val message: String)

// Keep a bounded in-memory log for the Daemons page alongside logcat.
object Logs {

    private val kept = ArrayDeque<LogLine>()

    fun d(tag: String, message: String) {
        Log.d("Strike/$tag", message)
        keep("debug", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w("Strike/$tag", message, error)
        keep("warn", tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e("Strike/$tag", message, error)
        keep("error", tag, message)
    }

    fun recent(): List<LogLine> = synchronized(kept) { kept.toList() }

    private fun keep(level: String, tag: String, message: String) {
        synchronized(kept) {
            if (kept.size == KEPT) kept.removeFirst()
            kept.addLast(LogLine(System.currentTimeMillis(), level, tag, message))
        }
    }
}
