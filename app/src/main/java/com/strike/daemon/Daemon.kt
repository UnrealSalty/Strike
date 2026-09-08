package com.strike.daemon

import android.content.Context
import com.strike.core.Logs

private const val TAG = "Daemon"

// Only shell uid 2000 can open the camera.
class Daemon(private val context: Context, private val shell: Shell) {

    @Synchronized
    fun start(): Boolean {
        val apk = apkPath() ?: return false
        if (!stop()) return false
        return launch(apk)
    }

    @Synchronized
    fun resume(): Boolean {
        val apk = apkPath() ?: return false
        return launch(apk)
    }

    private fun launch(apk: String): Boolean {
        val script = watchdogScript(
            context.packageName, apk, context.applicationInfo.nativeLibraryDir, DAEMON_CLASS
        )
        if (shell.run(writeScriptLine(script)) != 0) {
            return false
        }
        return shell.run(
            "rm -f $CAM_SENTINEL_PATH || exit 1; nohup sh $CAM_SCRIPT_PATH > /dev/null 2>&1 &"
        ) == 0
    }

    @Synchronized
    fun stop(shutdown: () -> Boolean = { false }, force: Boolean = true): Boolean {
        if (shell.run(stopWatchdogLine()) != 0) return false
        return shell.run(stopDaemonLine(shutdown(), force)) == 0
    }

    private fun apkPath(): String? {
        val path = apkFrom(shell.read("pm path ${context.packageName}"))
        if (path == null) Logs.w(TAG, "cannot locate Strike's own apk")
        return path
    }
}

internal fun stopWatchdogLine(): String =
    "mkdir -p $STRIKE_DIR || exit 1; chmod 755 $STRIKE_DIR; " +
        "echo stopped from the app > $CAM_SENTINEL_PATH || exit 1; chmod 644 $CAM_SENTINEL_PATH; " +
        killLine(CAM_SCRIPT_PATH) + "; rm -f $CAM_SCRIPT_PATH $CAM_WATCHDOG_PID_PATH"

internal fun stopDaemonLine(graceful: Boolean, force: Boolean = true): String =
    (if (graceful) "" else killLine(CAM_PROCESS, 15) + "; ") + "WAITED=0; " +
        "while pidof $CAM_PROCESS >/dev/null 2>&1 && [ \$WAITED -lt 20 ]; do " +
        "sleep 1; WAITED=\$((WAITED + 1)); done; " +
        (if (force) killLine(CAM_PROCESS) + "; " else "") +
        "if pidof $CAM_PROCESS >/dev/null 2>&1; then exit 1; fi; " +
        "rm -f $CAM_LOCK_PATH"

internal val DAEMON_CLASS: String = CameraDaemon::class.java.name

internal fun apkFrom(pmOutput: String?): String? {
    if (pmOutput == null) return null
    for (line in pmOutput.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("package:")) return trimmed.substring("package:".length)
    }
    return null
}
