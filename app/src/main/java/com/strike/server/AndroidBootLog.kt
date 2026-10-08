package com.strike.server

import com.strike.daemon.DaemonLog
import com.strike.daemon.Shell
import java.io.File

private const val ANDROID_LINES = 1500
private const val ANDROID_PATTERN = "com\\.strike|ssc_skip|AccModeManagerService|AccessibilityManager|" +
    "am_proc_start|am_proc_died|am_kill|am_broadcast_discard_app|Force stopping|boot_progress_ams_ready"

// Android keeps its log in memory only, so the parked restart is still there when the car next turns on.
internal class AndroidBootLog(private val file: File) {

    fun capture(shell: Shell) {
        val text = shell.read(
            "echo \"captured \$(date '+%Y-%m-%d %H:%M:%S') boot=\$(cat /proc/sys/kernel/random/boot_id) " +
                "uptime=\$(cut -d' ' -f1 /proc/uptime)s rebootreason=\$(getprop persist.sys.rebootreason) " +
                "quickboot=\$(getprop persist.sys.quickboot_ongoing)\"; " +
                "echo \"accessibility list: \$(timeout -s KILL 5 settings get secure enabled_accessibility_services)\"; " +
                "timeout -s KILL 10 dumpsys accessibility | grep -E 'Enabled services|Binding services|Bound services' | " +
                "cut -c1-400; " +
                "timeout -s KILL 20 logcat -d -b main -b system -b events | grep -E '$ANDROID_PATTERN' | " +
                "tail -n $ANDROID_LINES"
        )
        if (text == null) {
            DaemonLog.w("Boot", "Could not save Android's log of the last head unit start")
            return
        }
        file.writeText(text)
    }

    fun text(): String? = if (file.isFile) file.readText() else null
}
