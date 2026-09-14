package com.strike.vehicle

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

internal const val DILINK5_POWER_DUMP =
    "timeout -s KILL 2 sh -c 'set -o pipefail; dumpsys -t 1 car_service | head -c 524289'"

private const val MAX_DUMP_BYTES = 512 * 1024
private const val DUMP_TIMEOUT_MS = 1_500L
private val CURRENT = Regex("\\bcurrent\\b", RegexOption.IGNORE_CASE)
private val POWER_MODE = Regex("(?<![\\w-])(\\d+)\\s*=\\s*PowerMode\\s+([A-Za-z]+(?:[ \\t]+[A-Za-z]+)*)")

internal fun diLink5AccOnOf(dump: String?): Boolean? {
    if (dump == null || dump.length > MAX_DUMP_BYTES || dump.contains("DUMP TIMEOUT", true)) return null
    val lines = dump.lineSequence().toList()
    val sections = lines.indices.filter { lines[it].contains("Power Mute State", true) }
    if (sections.size != 1) return null
    val section = sections.single()
    val current = lines.subList(section, minOf(section + 4, lines.size))
        .filter { CURRENT.containsMatchIn(it) && !it.contains("All items", true) }
    if (current.size != 1) return null
    val line = current.single()
    val values = POWER_MODE.findAll(line).toList()
    if (values.size != 1) return null
    val value = values.single()
    if (CURRENT.find(line)!!.range.first > value.range.first) return null
    val mode = value.groupValues[1].toIntOrNull() ?: return null
    val name = value.groupValues[2].replace(Regex("[ \\t]+"), " ")
    return when (mode to name) {
        0 to "Off", 1 to "Pre StartUp", 4 to "Standby", 5 to "Str",
        8 to "Sleep", 9 to "Str Suspending", 12 to "Tod" -> false
        2 to "StartUp", 3 to "Degraded", 10 to "DisPlay on" -> true
        else -> null
    }
}

internal class DiLink5Power(
    private val readDump: () -> String?,
    private val timeoutMs: Long = DUMP_TIMEOUT_MS
) {
    private val lock = Any()
    private var inFlight: FutureTask<String?>? = null

    fun read(): String? {
        val task = synchronized(lock) {
            if (inFlight != null) return null
            FutureTask(readDump).also { pending ->
                inFlight = pending
                Thread({
                    try {
                        pending.run()
                    } finally {
                        synchronized(lock) { inFlight = null }
                    }
                }, "vehicle-power-query").also { it.isDaemon = true; it.start() }
            }
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        } catch (e: ExecutionException) {
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }
}

internal fun readDiLink5PowerDump(
    start: () -> Process = {
        ProcessBuilder("dumpsys", "-t", "1", "car_service").redirectErrorStream(true).start()
    },
    timeoutMs: Long = DUMP_TIMEOUT_MS
): String? {
    val process = try {
        start()
    } catch (e: IOException) {
        return null
    } catch (e: SecurityException) {
        return null
    }
    val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val output = AtomicReference<String?>()
    val drain = Thread({
        try {
            process.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_DUMP_BYTES) {
                        process.destroyForcibly()
                        return@Thread
                    }
                    bytes.write(buffer, 0, count)
                }
                output.set(bytes.toString("UTF-8"))
            }
        } catch (e: IOException) {
            output.set(null)
        }
    }, "vehicle-power-dump").also { it.isDaemon = true; it.start() }
    return try {
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return null
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0) return null
        TimeUnit.NANOSECONDS.timedJoin(drain, remainingNs)
        if (drain.isAlive) null else output.get()
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
