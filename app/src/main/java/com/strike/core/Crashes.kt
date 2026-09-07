package com.strike.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

private const val TAG = "Crash"
private const val FILE_NAME = "last-crash.txt"

/**
 * An uncaught exception on any thread ends the process, which takes the log
 * the owner reads with it. The trace is written to disk so the next launch can
 * say what happened, on a head unit with no way to read logcat.
 */
object Crashes {

    fun watch(dir: File) {
        val file = File(dir, FILE_NAME)
        report(file)
        val earlier = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText("${thread.name}\n${traceOf(error)}")
            } catch (e: Throwable) {
                // The process is ending either way.
            }
            earlier?.uncaughtException(thread, error)
        }
    }

    private fun report(file: File) {
        if (!file.isFile) return
        val text = file.readText().trim()
        file.delete()
        if (text.isEmpty()) return
        val thread = text.substringBefore('\n')
        Logs.e(TAG, "Strike stopped on thread $thread: ${headline(text)}")
    }

    /** The exception and the first frame in Strike, which is the useful pair. */
    private fun headline(text: String): String {
        val lines = text.lines().drop(1).map { it.trim() }
        val cause = lines.firstOrNull().orEmpty()
        val ours = lines.firstOrNull { it.startsWith("at com.strike") }
        return if (ours == null) cause else "$cause, $ours"
    }
}

internal fun traceOf(error: Throwable): String {
    val text = StringWriter()
    PrintWriter(text).use { error.printStackTrace(it) }
    return text.toString()
}
