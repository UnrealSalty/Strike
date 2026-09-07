package com.strike.daemon

import com.strike.core.LogLine

// The watchdog writes stdout to cam.log; the app parses the same format.
object DaemonLog {

    fun watchCrashes() {
        val earlier = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Daemon", "uncaught failure on ${thread.name}: $error")
            error.printStackTrace(System.out)
            System.out.flush()
            earlier?.uncaughtException(thread, error)
        }
    }

    fun d(tag: String, message: String) = say("debug", tag, message)

    fun w(tag: String, message: String) = say("warn", tag, message)

    fun e(tag: String, message: String) = say("error", tag, message)

    private fun say(level: String, tag: String, message: String) {
        println("${System.currentTimeMillis()} $level $tag $message")
        System.out.flush()
    }
}

// Non-daemon lines inherit the preceding timestamp for merged log ordering.
internal fun parseDaemonLog(text: String): List<LogLine> {
    val lines = ArrayList<LogLine>()
    var atMs = 0L
    for (raw in text.lineSequence()) {
        val line = raw.trimEnd()
        if (line.isEmpty()) continue
        val parts = line.split(' ', limit = 4)
        val stamp = if (parts.size == 4) parts[0].toLongOrNull() else null
        if (stamp != null) {
            atMs = stamp
            lines.add(LogLine(stamp, parts[1], parts[2], parts[3]))
        } else {
            lines.add(LogLine(atMs, "error", "daemon", line))
        }
    }
    return lines
}
