package com.strike.daemon

private const val LOG_MAX_BYTES = 5_242_880L
private const val LOG_CHECK_SECONDS = 3_600
private const val DASHBOARD_CHECK_SECONDS = 60
private const val HEALTHY_UPTIME_SEC = 300
private const val MAX_RETRY_DELAY_SEC = 60

// The app sleeps with the car, so the shell owns recovery.
internal fun watchdogScript(
    packageName: String,
    apkPath: String,
    nativeLibDir: String,
    daemonClass: String,
    installation: String = ""
): List<String> {
    // app_process needs the extracted native library and the apk's assets.
    val launch = "  CLASSPATH=/system/framework/bmmcamera.jar:\$APK_PATH app_process " +
        "-Djava.library.path=$nativeLibDir:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 " +
        "/system/bin --nice-name=$CAM_PROCESS $daemonClass $nativeLibDir \"\$APK_PATH\" " +
        ">> \"\$LOG_FILE\" 2>&1 &"

    return listOf(
        "#!/system/bin/sh",
        "INSTALLATION='$installation'",
        "LOG_FILE=\"$CAM_LOG_PATH\"",
        "SENTINEL=\"$CAM_SENTINEL_PATH\"",
        "PID_FILE=\"$CAM_WATCHDOG_PID_PATH\"",
        "FALLBACK_APK=\"$apkPath\"",
        "RETRY_COUNT=0",
        "APK_WAITING=0",
        *dashboardRecoveryLines(installation).toTypedArray(),
        "echo \$\$ > \"\$PID_FILE\"",
        "MAINTENANCE_PID=",
        "trap 'if [ -n \"\$MAINTENANCE_PID\" ]; then kill \$MAINTENANCE_PID 2>/dev/null; wait \$MAINTENANCE_PID 2>/dev/null; fi; " +
            "if [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" = \"\$\$\" ]; then rm -f \"\$PID_FILE\"; fi' EXIT",
        "(",
        "  trap - EXIT",
        "  LOG_TICKS=0",
        "  SLEEP_PID=",
        "  trap 'kill \$SLEEP_PID 2>/dev/null; exit 0' TERM",
        "  while kill -0 \$\$ 2>/dev/null; do",
        "    sleep $DASHBOARD_CHECK_SECONDS &",
        "    SLEEP_PID=\$!",
        "    wait \$SLEEP_PID",
        "    if [ -f \"\$SENTINEL\" ] || [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" != \"\$\$\" ]; then break; fi",
        "    kill -0 \$\$ 2>/dev/null || break",
        "    recover_dashboard",
        "    LOG_TICKS=\$((LOG_TICKS + 1))",
        "    if [ \$LOG_TICKS -ge ${LOG_CHECK_SECONDS / DASHBOARD_CHECK_SECONDS} ]; then",
        *truncateLines("      "),
        "      LOG_TICKS=0",
        "    fi",
        "  done",
        ") &",
        "MAINTENANCE_PID=\$!",
        "while true; do",
        *truncateLines("  "),
        "  if [ -f \"\$SENTINEL\" ] || [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" != \"\$\$\" ]; then",
        "    exit 0",
        "  fi",
        // Reuse a daemon left running by an earlier watchdog.
        "  if pidof $CAM_PROCESS >/dev/null 2>&1; then",
        "    sleep 10",
        "    continue",
        "  fi",
        "  APK_PATH=\$(timeout -s KILL 5 cmd package path $packageName 2>/dev/null | grep '/base.apk\$' | head -n 1 | sed 's/^package://')",
        "  if [ ! -f \"\$APK_PATH\" ] && [ -f \"\$FALLBACK_APK\" ]; then APK_PATH=\"\$FALLBACK_APK\"; fi",
        "  if [ ! -f \"\$APK_PATH\" ]; then",
        "    if PACKAGES=\$(timeout -s KILL 5 cmd package list packages $packageName 2>/dev/null); then",
        "      if ! printf '%s\\n' \"\$PACKAGES\" | grep -Fx 'package:$packageName' >/dev/null; then exit 0; fi",
        "    fi",
        "    if [ \$APK_WAITING -eq 0 ]; then",
        "      echo \"\$(date +%s)000 warn watchdog waiting for Strike's APK or package service\" >> \"\$LOG_FILE\"",
        "    fi",
        "    APK_WAITING=1",
        "    sleep 10",
        "    continue",
        "  fi",
        "  APK_WAITING=0",
        "  if [ -f \"\$SENTINEL\" ] || [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" != \"\$\$\" ]; then exit 0; fi",
        "  FALLBACK_APK=\"\$APK_PATH\"",
        "  START=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
        launch,
        "  DAEMON_PID=\$!",
        "  wait \$DAEMON_PID",
        "  EXIT_CODE=\$?",
        "  END=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
        "  UPTIME_SEC=\$((END - START))",
        "  if [ \$UPTIME_SEC -lt 0 ]; then UPTIME_SEC=0; fi",
        "  if [ -f \"\$SENTINEL\" ] || [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" != \"\$\$\" ]; then",
        "    exit 0",
        "  fi",
        "  if [ \$UPTIME_SEC -ge $HEALTHY_UPTIME_SEC ]; then",
        "    RETRY_COUNT=0",
        "  else",
        "    RETRY_COUNT=\$((RETRY_COUNT + 1))",
        "  fi",
        "  DELAY=\$((RETRY_COUNT * 3))",
        "  if [ \$DELAY -eq 0 ]; then DELAY=3; fi",
        "  if [ \$DELAY -gt $MAX_RETRY_DELAY_SEC ]; then DELAY=$MAX_RETRY_DELAY_SEC; fi",
        "  echo \"\$(date +%s)000 warn watchdog daemon exited with \$EXIT_CODE after \${UPTIME_SEC}s, " +
            "waiting \${DELAY}s\" >> \"\$LOG_FILE\"",
        "  sleep \$DELAY",
        "done"
    )
}

internal fun dashboardRecoveryLines(installation: String): List<String> {
    if (!installation.matches(Regex("[0-9]+:[0-9]+"))) return listOf("recover_dashboard() { return 1; }")
    val script = "$STRIKE_DIR/dashboard-${installation.replace(':', '-')}/start.sh"
    val wanted = "(${recorderDesiredGuard(installation)}; " +
        "[ \"\$(cat '$CAM_WATCHDOG_PID_PATH' 2>/dev/null)\" = \"\$\$\" ]) || return 1"
    return listOf(
        "DASHBOARD_RECOVERY_LOGGED=0",
        "DASHBOARD_START_PID=",
        "recover_dashboard() {",
        "  $wanted",
        "  if pidof strike_dashboard >/dev/null 2>&1; then DASHBOARD_RECOVERY_LOGGED=0; return 0; fi",
        "  [ -f '$script' ] || return 1",
        "  if [ -n \"\$DASHBOARD_START_PID\" ] && kill -0 \"\$DASHBOARD_START_PID\" 2>/dev/null && " +
            "tr '\\000' '\\n' < \"/proc/\$DASHBOARD_START_PID/cmdline\" 2>/dev/null | grep -Fx '$script' >/dev/null; then return 0; fi",
        "  $wanted",
        "  nohup sh '$script' </dev/null >/dev/null 2>&1 &",
        "  DASHBOARD_START_PID=\$!",
        "  if [ \$DASHBOARD_RECOVERY_LOGGED -eq 0 ]; then",
        "    echo \"\$(date +%s)000 warn watchdog dashboard missing; requested restart\" >> '$CAM_LOG_PATH'",
        "    DASHBOARD_RECOVERY_LOGGED=1",
        "  fi",
        "}"
    )
}

private fun truncateLines(indent: String): Array<String> = arrayOf(
    "${indent}if [ -f \"\$LOG_FILE\" ]; then",
    "$indent  LOG_SZ=\$(stat -c%s \"\$LOG_FILE\" 2>/dev/null || echo 0)",
    "$indent  if [ \"\$LOG_SZ\" -gt $LOG_MAX_BYTES ]; then",
    "$indent    : > \"\$LOG_FILE\"",
    "$indent  fi",
    "${indent}fi"
)

// Write lines individually for the head unit's shell.
internal fun writeScriptLine(lines: List<String>): String {
    val command = StringBuilder("(SCRIPT_TMP=\"$CAM_SCRIPT_PATH.\$\$.tmp\"; ")
    command.append("trap 'rm -f \"\$SCRIPT_TMP\"' EXIT; ")
    for ((index, line) in lines.withIndex()) {
        val escaped = line
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        command.append("printf '%s\\n' \"$escaped\" ")
        command.append(if (index == 0) "> " else ">> ")
        command.append("\"\$SCRIPT_TMP\" || exit 1; ")
    }
    command.append("chmod 755 \"\$SCRIPT_TMP\" && mv -f \"\$SCRIPT_TMP\" '$CAM_SCRIPT_PATH')")
    return command.toString()
}

internal fun killLine(pattern: String, signal: Int = 9): String =
    "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F '$pattern' | grep -v grep | awk '{print \$1}' | " +
        "while read pid; do if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -$signal \$pid 2>/dev/null; fi; done"
