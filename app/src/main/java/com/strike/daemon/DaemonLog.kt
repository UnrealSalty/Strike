package com.strike.daemon

import com.strike.core.LogLine

/**
 * The watchdog redirects the daemon's stdout into cam.log, so writing a line
 * is a println. The app parses those lines back out, which is why both ends of
 * the format live in this one file.
 */
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

/**
 * Lines the daemon did not write itself, a crash trace or a shell error, keep
 * the timestamp of the line above so they stay in place when merged.
 */
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
