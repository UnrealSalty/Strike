package com.strike.daemon

private const val LOG_MAX_BYTES = 5_242_880L
private const val LOG_CHECK_SECONDS = 3_600
private const val HEALTHY_UPTIME_SEC = 300
private const val MAX_SHORT_RUNS = 5

// The app sleeps with the car, so the shell owns recovery.
internal fun watchdogScript(
    packageName: String,
    apkPath: String,
    nativeLibDir: String,
    daemonClass: String
): List<String> {
    // app_process needs the extracted native library and the apk's assets.
    val launch = "  CLASSPATH=/system/framework/bmmcamera.jar:\$APK_PATH app_process " +
        "-Djava.library.path=$nativeLibDir:/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 " +
        "/system/bin --nice-name=$CAM_PROCESS $daemonClass $nativeLibDir \"\$APK_PATH\" " +
        ">> \"\$LOG_FILE\" 2>&1 &"

    return listOf(
        "#!/system/bin/sh",
        "LOG_FILE=\"$CAM_LOG_PATH\"",
        "LOCK_FILE=\"$CAM_LOCK_PATH\"",
        "SENTINEL=\"$CAM_SENTINEL_PATH\"",
        "PID_FILE=\"$CAM_WATCHDOG_PID_PATH\"",
        "FALLBACK_APK=\"$apkPath\"",
        "RETRY_COUNT=0",
        "echo \$\$ > \"\$PID_FILE\"",
        "trap 'if [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" = \"\$\$\" ]; then rm -f \"\$PID_FILE\"; fi' EXIT",
        "while true; do",
        *truncateLines("  "),
        "  if [ -f \"\$SENTINEL\" ] || [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" != \"\$\$\" ]; then",
        "    exit 0",
        "  fi",
        // Reuse a daemon left running by an earlier watchdog.
        "  OWNER=\$(cat \"\$LOCK_FILE\" 2>/dev/null)",
        "  if [ -n \"\$OWNER\" ] && [ -d \"/proc/\$OWNER\" ]; then",
        "    sleep 10",
        "    continue",
        "  fi",
        "  APK_PATH=\$(pm path $packageName 2>/dev/null | grep '/base.apk\$' | head -n 1 | sed 's/^package://')",
        "  if [ -z \"\$APK_PATH\" ] && [ -f \"\$FALLBACK_APK\" ]; then APK_PATH=\"\$FALLBACK_APK\"; fi",
        "  if [ -z \"\$APK_PATH\" ]; then",
        "    echo \"Strike apk is unavailable. Start the recorder after installation finishes\" > \"\$SENTINEL\"",
        "    chmod 644 \"\$SENTINEL\"",
        "    echo \"\$(date +%s)000 error watchdog \$(cat \"\$SENTINEL\")\" >> \"\$LOG_FILE\"",
        "    exit 1",
        "  fi",
        "  START=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
        launch,
        "  DAEMON_PID=\$!",
        "  (",
        "    trap 'kill \$SLEEP_PID 2>/dev/null; exit 0' TERM",
        "    while kill -0 \$DAEMON_PID 2>/dev/null; do",
        "      sleep $LOG_CHECK_SECONDS &",
        "      SLEEP_PID=\$!",
        "      wait \$SLEEP_PID",
        *truncateLines("      "),
        "    done",
        "  ) &",
        "  ROTATE_PID=\$!",
        "  wait \$DAEMON_PID",
        "  EXIT_CODE=\$?",
        "  kill \$ROTATE_PID 2>/dev/null",
        "  wait \$ROTATE_PID 2>/dev/null",
        "  END=\$(awk '{print int(\$1)}' /proc/uptime 2>/dev/null || date +%s)",
        "  UPTIME_SEC=\$((END - START))",
        "  if [ \$UPTIME_SEC -lt 0 ]; then UPTIME_SEC=0; fi",
        "  if [ -f \"\$SENTINEL\" ] || [ \"\$(cat \"\$PID_FILE\" 2>/dev/null)\" != \"\$\$\" ]; then",
        "    exit 0",
        "  fi",
        "  if [ \$EXIT_CODE -eq $EXIT_ALREADY_RUNNING ]; then",
        "    echo \"\$(date +%s)000 debug watchdog another daemon owns the camera, standing down\" >> \"\$LOG_FILE\"",
        "    exit 0",
        "  fi",
        "  if [ \$UPTIME_SEC -ge $HEALTHY_UPTIME_SEC ]; then",
        "    RETRY_COUNT=0",
        "  else",
        "    RETRY_COUNT=\$((RETRY_COUNT + 1))",
        "  fi",
        "  if [ \$RETRY_COUNT -ge $MAX_SHORT_RUNS ]; then",
        "    echo \"Recorder stopped after $MAX_SHORT_RUNS short runs (exit \$EXIT_CODE). Start it to retry\" > \"\$SENTINEL\"",
        "    chmod 644 \"\$SENTINEL\"",
        "    echo \"\$(date +%s)000 error watchdog \$(cat \"\$SENTINEL\")\" >> \"\$LOG_FILE\"",
        "    exit 1",
        "  fi",
        "  DELAY=\$((RETRY_COUNT * 3))",
        "  if [ \$DELAY -eq 0 ]; then DELAY=3; fi",
        "  echo \"\$(date +%s)000 warn watchdog daemon exited with \$EXIT_CODE after \${UPTIME_SEC}s, " +
            "waiting \${DELAY}s\" >> \"\$LOG_FILE\"",
        "  sleep \$DELAY",
        "done"
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
    val command = StringBuilder("rm -f $CAM_SCRIPT_PATH 2>/dev/null; ")
    for ((index, line) in lines.withIndex()) {
        val escaped = line
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        command.append("echo \"$escaped\" ")
        command.append(if (index == 0) "> " else ">> ")
        command.append("$CAM_SCRIPT_PATH; ")
    }
    command.append("chmod 755 $CAM_SCRIPT_PATH")
    return command.toString()
}

internal fun killLine(pattern: String, signal: Int = 9): String =
    "MY_PID=\$\$; ps -A -o PID,ARGS | grep -F '$pattern' | grep -v grep | awk '{print \$1}' | " +
        "while read pid; do if [ \"\$pid\" != \"\$MY_PID\" ]; then kill -$signal \$pid 2>/dev/null; fi; done"
