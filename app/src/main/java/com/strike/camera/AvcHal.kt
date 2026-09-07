package com.strike.camera

import com.strike.daemon.DaemonLog
import java.util.concurrent.TimeUnit

private const val TAG = "AvcHal"

/**
 * NEW_TASK with NO_ANIMATION. Not REORDER_TO_FRONT, which would pop the car's
 * own camera screen over whatever the driver is looking at on every poke.
 */
private val LAUNCH = arrayOf(
    "am", "start", "--user", "0", "-n", "com.byd.avc/.MainActivity", "-f", "0x10010000"
)

private const val SETTLE_MS = 4_000L
private const val POKE_EVERY_MS = 60_000L
private const val COMMAND_TIMEOUT_S = 5L

/**
 * The panoramic HAL only fills its producer surface while the car's own camera
 * app is attached to it as well, so Strike opens the camera second and never
 * first. BYD stops that app when it has been idle, which takes the frames with
 * it, so it is poked for as long as Strike is holding the camera.
 */
object AvcHal {

    @Volatile
    private var pokedAtMs = 0L

    /** Blocks while the HAL comes up, so the caller opens into a warm one. */
    fun warmAndWait() {
        pokedAtMs = System.currentTimeMillis()
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

    // A wedged system_server never answers am, and waiting on that forever
    // would strand the camera, so the wait is bounded and the child killed.
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
