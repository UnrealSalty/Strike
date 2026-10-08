package com.strike.daemon

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.PowerManager
import android.os.Process
import com.strike.RecorderRevival
import com.strike.RecorderRevivalJob
import com.strike.core.Logs
import java.io.IOException

private const val TAG = "Daemon"

// Only shell uid 2000 can open the camera.
class Daemon(private val context: Context, private val shell: Shell) {

    private val installation = installation(context.packageManager.getPackageInfo(context.packageName, 0))
    private var revivalRequestResult: Boolean? = null
    private var bootStartWarning: String? = null

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

    @Synchronized
    fun recover(): Boolean {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        if (installation(installed) != installation) return false
        keepBootStart()
        val current = installed.applicationInfo ?: return false
        val stopped = current.flags and ApplicationInfo.FLAG_STOPPED != 0
        if (stopped || shell.check(recorderRecoveryMissingLine(installation, current.uid))) {
            requestAndroidRecovery(if (stopped) "app stopped" else "backup job missing", current.uid / 100_000)
        } else {
            revivalRequestResult = null
        }
        if (!shell.check(watchdogRecoveryGuard(installation))) return false
        val script = watchdogScript(
            context.packageName, current.sourceDir, current.nativeLibraryDir, DAEMON_CLASS, installation
        )
        return when (shell.run(recoverWatchdogLine(installation, script), logFailure = false)) {
            0 -> true
            1 -> false
            else -> throw IOException("The recorder watchdog did not stay running")
        }
    }

    private fun keepBootStart() {
        val code = shell.run("(" + recorderDesiredGuard(installation) + ") || exit 0; " + bootStartLine(),
            logFailure = false)
        val message = when (code) {
            0 -> null
            BOOT_START_RESTORED -> "Strike was no longer set to restart after a head unit restart. Fixed"
            else -> BOOT_START_FAILED
        }
        if (message == bootStartWarning) return
        bootStartWarning = message
        if (message == null) return
        if (Process.myUid() == 2000) DaemonLog.w(TAG, message) else Logs.w(TAG, message)
    }

    private fun installation(installed: PackageInfo): String =
        "${checkNotNull(installed.applicationInfo).uid}:${installed.firstInstallTime}"

    private fun requestAndroidRecovery(reason: String, user: Int) {
        val result = when (shell.run(recorderRevivalLine(installation, user), logFailure = false)) {
            0 -> true
            1 -> return
            else -> false
        }
        if (result == revivalRequestResult) return
        revivalRequestResult = result
        if (Process.myUid() == 2000) {
            if (result) DaemonLog.d(TAG, "Requested Android recorder recovery: $reason")
            else DaemonLog.w(TAG, "Could not request Android recorder recovery: $reason")
        } else {
            if (result) Logs.d(TAG, "Requested Android recorder recovery: $reason")
            else Logs.w(TAG, "Could not request Android recorder recovery: $reason")
        }
    }

    private fun launch(apk: String): Boolean {
        val script = watchdogScript(
            context.packageName, apk, context.applicationInfo.nativeLibraryDir, DAEMON_CLASS, installation
        )
        if (shell.run(writeScriptLine(script)) != 0) {
            return false
        }
        val started = shell.run("rm -f '$CAM_SENTINEL_PATH' || exit 1; " + launchWatchdogLine()) == 0
        if (started) revival(true)
        return started
    }

    @Synchronized
    fun stop(shutdown: () -> Boolean = { false }, force: Boolean = true): Boolean {
        if (shell.run(stopWatchdogLine()) != 0) return false
        revival(false)
        return shell.run(stopDaemonLine(shutdown(), force)) == 0
    }

    private fun revival(enabled: Boolean) {
        if (enabled) {
            allowIdleRecovery()
            val code = shell.run(bootStartLine(), logFailure = false)
            if (code != 0 && code != BOOT_START_RESTORED) Logs.w(TAG, BOOT_START_FAILED)
        }
        if (Process.myUid() != 2000) {
            RecorderRevival.setEnabled(context, enabled)
        } else if (!shell.check("timeout -s KILL 5 am broadcast --receiver-foreground " +
                "-n com.strike/.RecorderRevival -a com.strike.RECORDER_REVIVAL_STATE --ez enabled $enabled")) {
            Logs.w(TAG, "Could not update Android recorder recovery")
        }
    }

    private fun allowIdleRecovery() {
        val allowed = try {
            val power = context.getSystemService(PowerManager::class.java)
            if (power.isIgnoringBatteryOptimizations("com.strike")) return
            shell.check("timeout -s KILL 5 dumpsys deviceidle whitelist +com.strike")
            power.isIgnoringBatteryOptimizations("com.strike")
        } catch (e: RuntimeException) {
            false
        }
        if (!allowed) Logs.w(TAG, "Android battery restrictions may delay recorder recovery")
    }

    private fun apkPath(): String? {
        val path = apkFrom(shell.read("pm path ${context.packageName}"))
        if (path == null) Logs.w(TAG, "cannot locate Strike's own apk")
        return path
    }
}

internal fun recorderDesiredGuard(installation: String): String =
    "[ -f '$CAM_SCRIPT_PATH' ] && [ ! -f '$CAM_SENTINEL_PATH' ] || exit 1; " +
        "grep -Fx \"INSTALLATION='$installation'\" '$CAM_SCRIPT_PATH' >/dev/null 2>&1 || exit 1"

internal fun recorderRecoveryMissingLine(installation: String, appUid: Int): String {
    val user = appUid / 100_000
    val job = RecorderRevivalJob.JOB_ID
    return recorderDesiredGuard(installation) + "; " +
        "RECOVERY_JOB=\$(timeout -s KILL 5 cmd jobscheduler get-job-state --user $user com.strike $job 2>&1); " +
        "RECOVERY_CODE=\$?; [ \"\$RECOVERY_CODE\" -eq 23 ] && " +
        "printf '%s\\n' \"\$RECOVERY_JOB\" | " +
        "grep -Fx 'Could not find job $job in package com.strike / user $user' >/dev/null"
}

internal fun recorderRevivalLine(installation: String, user: Int): String =
    recorderDesiredGuard(installation) + "; " +
        "timeout -s KILL 5 am broadcast --user $user --include-stopped-packages --receiver-foreground " +
        "-n com.strike/.RecorderRevival -a ${RecorderRevival.ACTION_WAKE} >/dev/null 2>&1 || exit 2"

private const val BOOT_START = "com.strike/.BootStart"
private const val BOOT_START_FAILED = "Strike may not restart by itself after the head unit restarts"

internal const val BOOT_START_RESTORED = 3

internal fun bootStartLine(): String =
    "ADDED=; SERVICES=\$(timeout -s KILL 5 settings get secure enabled_accessibility_services) || exit 1; " +
        "case \":\$SERVICES:\" in " +
        "*':$BOOT_START:'*|*':com.strike/com.strike.BootStart:'*) ;; " +
        "':null:'|'::') ADDED=1; timeout -s KILL 5 settings put secure enabled_accessibility_services '$BOOT_START' || exit 1;; " +
        "*) ADDED=1; timeout -s KILL 5 settings put secure enabled_accessibility_services \"\$SERVICES:$BOOT_START\" || exit 1;; " +
        "esac; " +
        "timeout -s KILL 5 settings put secure accessibility_enabled 1 || exit 1; " +
        "SERVICES=\$(timeout -s KILL 5 settings get secure enabled_accessibility_services) || exit 1; " +
        "case \":\$SERVICES:\" in *':$BOOT_START:'*|*':com.strike/com.strike.BootStart:'*) " +
        "[ -z \"\$ADDED\" ] || exit $BOOT_START_RESTORED; exit 0;; esac; exit 1"

internal fun watchdogRecoveryGuard(installation: String): String =
    recorderDesiredGuard(installation) + "; " +
        "pidof $CAM_PROCESS >/dev/null 2>&1 && exit 1; " +
        "WATCHDOG_PID=\$(cat '$CAM_WATCHDOG_PID_PATH' 2>/dev/null); " +
        "if " + watchdogAliveLine() + "; then exit 1; fi"

internal fun recorderRunningLine(): String =
    "pidof $CAM_PROCESS >/dev/null 2>&1 || (" +
        "WATCHDOG_PID=\$(cat '$CAM_WATCHDOG_PID_PATH' 2>/dev/null); " + watchdogAliveLine() + ")"

private fun watchdogAliveLine(): String =
    "(case \"\$WATCHDOG_PID\" in ''|*[!0-9]*) exit 1;; esac; " +
        "kill -0 \"\$WATCHDOG_PID\" 2>/dev/null && " +
        "tr '\\000' '\\n' 2>/dev/null < /proc/\$WATCHDOG_PID/cmdline | grep -Fx '$CAM_SCRIPT_PATH' >/dev/null)"

internal fun launchWatchdogLine(): String =
    "[ ! -f '$CAM_SENTINEL_PATH' ] || exit 1; " +
        "nohup sh '$CAM_SCRIPT_PATH' </dev/null >/dev/null 2>&1 & " +
        "STARTED_PID=\$!; " +
        "for ATTEMPT in 1 2 3; do " +
        "sleep 1; " +
        "[ ! -f '$CAM_SENTINEL_PATH' ] || exit 1; " +
        "WATCHDOG_PID=\$(cat '$CAM_WATCHDOG_PID_PATH' 2>/dev/null); " +
        "if [ \"\$WATCHDOG_PID\" = \"\$STARTED_PID\" ] && " + watchdogAliveLine() + "; then exit 0; fi; " +
        "kill -0 \"\$STARTED_PID\" 2>/dev/null || exit 2; " +
        "done; exit 2"

internal fun recoverWatchdogLine(installation: String, script: List<String>): String =
    watchdogRecoveryGuard(installation) + "; " + writeScriptLine(script) + " || exit 2; " +
        watchdogRecoveryGuard(installation) + "; (" + launchWatchdogLine() + "); " +
        "LAUNCH_CODE=\$?; [ \"\$LAUNCH_CODE\" -eq 0 ] || exit \"\$LAUNCH_CODE\"; " +
        "echo \"\$(date +%s)000 warn watchdog recorder stopped; watchdog restarted\" >> '$CAM_LOG_PATH'"

internal fun stopWatchdogLine(): String =
    "mkdir -p $STRIKE_DIR || exit 1; ${strikeDirSetup()}; chmod 755 $STRIKE_DIR; " +
        "echo stopped from the app > $CAM_SENTINEL_PATH || exit 1; chmod 644 $CAM_SENTINEL_PATH; " +
        killLine(CAM_SCRIPT_PATH) + "; rm -f $CAM_SCRIPT_PATH $CAM_WATCHDOG_PID_PATH $CAM_RECOVERY_PATH $CAM_RECOVERY_PATH.tmp " +
        "$CAM_POWER_RECOVERY_PATH $CAM_POWER_RECOVERY_PATH.tmp"

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
