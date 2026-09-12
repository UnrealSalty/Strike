package com.strike.camera

import com.strike.daemon.DaemonLog
import java.util.concurrent.TimeUnit

private const val TAG = "AvcHal"

// REORDER_TO_FRONT would bring the OEM camera screen over the dashboard.
private val LAUNCH = arrayOf(
    "am", "start", "--user", "0", "-n", "com.byd.avc/.MainActivity", "-f", "0x10010000"
)

private const val SETTLE_MS = 4_000L
private const val POKE_EVERY_MS = 60_000L
private const val COMMAND_TIMEOUT_S = 5L

// Some AVM firmware needs the OEM camera app attached to keep producing frames.
object AvcHal {

    @Volatile
    private var pokedAtMs = 0L

    fun warmAndWait() {
        pokedAtMs = System.currentTimeMillis()
        if (running()) return
        if (!launch()) return
        Thread.sleep(SETTLE_MS)
    }

    fun keepAlive(everyMs: Long = POKE_EVERY_MS) {
        if (System.currentTimeMillis() - pokedAtMs < everyMs) return
        pokedAtMs = System.currentTimeMillis()
        if (running()) return
        launch()
    }

    private fun running(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("pidof", "com.byd.avc"))
            if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return false
            }
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().use { it.readText() }.isNotBlank()
        } catch (t: Throwable) {
            process?.destroyForcibly()
            false
        }
    }

    // Bound am's runtime so an unresponsive system_server cannot block capture startup.
    private fun launch(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(LAUNCH)
            if (!process.waitFor(COMMAND_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                DaemonLog.w(TAG, "the car's camera app did not answer in ${COMMAND_TIMEOUT_S}s")
                return false
            }
            val code = process.exitValue()
            if (code != 0) {
                DaemonLog.w(TAG, "the car's camera app would not start, am exited $code")
            }
            code == 0
        } catch (t: Throwable) {
            process?.destroyForcibly()
            DaemonLog.w(TAG, "the car's camera app would not start: ${t.javaClass.simpleName}")
            false
        }
    }
}
