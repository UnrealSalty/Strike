package com.strike.online

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One way of reaching the car from outside the local network. Exactly one runs at a time;
 * [Online] owns the schedule, the wake locks and the restarts for all of them.
 */
interface RemoteMethod {
    /** Shown while the child process is starting. */
    val connecting: String

    /** Non-null when the method cannot get any further on its own. */
    val problem: String?

    /** Runs outside the supervisor lock, before [start], so slow setup never blocks status reads. */
    fun prepare()

    fun start(): Process

    fun ready(): Boolean

    /** Maps one line of child output to a message worth showing, or null to ignore it. */
    fun failure(line: String): String?

    /** Where the car answers once the method is up. */
    fun address(): String?
}

internal class CommandOutput(val code: Int, val output: String)

internal fun runCommand(builder: ProcessBuilder, timeoutMs: Long): CommandOutput {
    builder.redirectErrorStream(true)
    val process = builder.start()
    val text = StringBuffer()
    val pump = Thread({
        try {
            process.inputStream.bufferedReader().forEachLine { text.append(it).append('\n') }
        } catch (e: IOException) {
            // Killing the command on timeout closes its output pipe.
        }
    }, "strike-online-command")
    pump.isDaemon = true
    pump.start()
    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
    }
    pump.join(1_000L)
    return CommandOutput(if (finished) process.exitValue() else -1, text.toString().trim())
}

internal fun commandProblem(output: String, fallback: String): String {
    val line = output.lines().lastOrNull { it.isNotBlank() }?.trim() ?: return fallback
    return line.take(160)
}
