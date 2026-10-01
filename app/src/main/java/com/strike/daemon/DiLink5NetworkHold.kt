package com.strike.daemon

import android.os.SystemClock
import com.strike.DiLink5NetworkReceiver
import com.strike.core.Logs
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

internal class DiLink5NetworkHold(
    private val send: (String, Long) -> Int = ::requestNetworkHold,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val report: (Boolean, String) -> Unit = { confirmed, message ->
        if (confirmed) Logs.d("DiLink5", message) else Logs.w("DiLink5", message)
    },
    private val execute: (Runnable) -> Unit = Executors.newSingleThreadExecutor {
        Thread(it, "dilink5-network-bridge").apply { isDaemon = true }
    }::execute
) {
    private data class Request(val enabled: Boolean, val parked: Boolean, val accOn: Boolean?, val atMs: Long)
    private val gate = Any()
    private var pending: Request? = null
    private var running = false
    private var previous: Pair<String, Int>? = null
    private var attemptedAtMs: Long? = null

    fun update(enabled: Boolean, parked: Boolean, accOn: Boolean?) = synchronized(gate) {
        pending = Request(enabled, parked, accOn, nowMs())
        if (!running) {
            running = true
            execute(Runnable {
                while (true) {
                    val next = synchronized(gate) {
                        pending.also {
                            pending = null
                            if (it == null) running = false
                        }
                    } ?: break
                    apply(next)
                }
            })
        }
    }

    private fun apply(request: Request) {
        val command = when {
            request.accOn == true -> "detach"
            !request.enabled -> "stop"
            request.parked -> "start"
            request.accOn == false -> "stop"
            else -> return
        }
        if (previous?.first == command) {
            if (command != "start" && previous?.second == DiLink5NetworkReceiver.CONFIRMED) return
            if (attemptedAtMs?.let { nowMs() - it < 30_000L } == true) return
        }
        attemptedAtMs = nowMs()
        val result = try {
            send(command, request.atMs)
        } catch (e: RuntimeException) {
            DiLink5NetworkReceiver.UNAVAILABLE
        }
        val next = command to result
        if (next != previous) {
            val message = when (result) {
                DiLink5NetworkReceiver.CONFIRMED -> when (command) {
                    "start" -> "Parked network keepalive confirmed by QNX"
                    "stop" -> if (previous?.first == "start") "Parked network keepalive released" else null
                    else -> if (previous?.first == "start") "Car is on; parked network keepalive detached" else null
                }
                DiLink5NetworkReceiver.LOW_VOLTAGE -> "Parked network keepalive paused: low 12 V battery"
                DiLink5NetworkReceiver.WAITING_VOLTAGE -> "Parked network keepalive waiting for a fresh 12 V reading"
                DiLink5NetworkReceiver.UNCONFIRMED -> "Parked network $command request was not confirmed; will retry"
                else -> "Parked network $command request unavailable; will retry"
            }
            if (message != null) report(result == DiLink5NetworkReceiver.CONFIRMED, message)
        }
        previous = next
    }
}

internal fun networkReply(exitCode: Int, output: String): Int {
    if (exitCode != 0 || output.length > 4096) return DiLink5NetworkReceiver.UNAVAILABLE
    val replies = Regex("(?m)^Broadcast completed: result=([+-]?\\d+)(?:,.*)?\\s*$").findAll(output).toList()
    val result = replies.singleOrNull()?.groupValues?.get(1)?.toIntOrNull()
    return result?.takeIf { it in 1..5 } ?: DiLink5NetworkReceiver.UNAVAILABLE
}

private fun requestNetworkHold(command: String, sentAtMs: Long): Int {
    require(command in listOf("start", "stop", "detach"))
    val reply = try {
        File.createTempFile("qnx-", ".reply", File(STRIKE_DIR))
    } catch (e: IOException) {
        return DiLink5NetworkReceiver.UNAVAILABLE
    }
    var process: Process? = null
    return try {
        process = ProcessBuilder("timeout", "-s", "KILL", "12", "am", "broadcast", "-W",
            "--receiver-foreground", "-n", "com.strike/.DiLink5NetworkReceiver",
            "-a", DiLink5NetworkReceiver.ACTION, "--es", "command", command,
            "--el", "sentAtMs", sentAtMs.toString())
            .redirectErrorStream(true).redirectOutput(reply).start()
        if (!process.waitFor(13, TimeUnit.SECONDS)) DiLink5NetworkReceiver.UNCONFIRMED
        else if (reply.length() > 4096) DiLink5NetworkReceiver.UNAVAILABLE
        else networkReply(process.exitValue(), reply.readText())
    } catch (e: IOException) {
        DiLink5NetworkReceiver.UNAVAILABLE
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        DiLink5NetworkReceiver.UNCONFIRMED
    } finally {
        if (process?.isAlive == true) process.destroyForcibly()
        reply.delete()
    }
}
