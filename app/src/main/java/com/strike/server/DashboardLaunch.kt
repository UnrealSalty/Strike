package com.strike.server

import android.content.Context
import com.strike.daemon.Shell
import com.strike.daemon.STRIKE_DIR
import java.io.File

internal fun dashboardDirectory(context: Context): String {
    val installed = context.packageManager.getPackageInfo(context.packageName, 0)
    return "$STRIKE_DIR/dashboard-${context.applicationInfo.uid}-${installed.firstInstallTime}"
}

internal fun launchDashboard(context: Context, shell: Shell): Boolean {
    val directory = dashboardDirectory(context)
    val script = File(context.cacheDir, "start-dashboard.sh")
    script.writeText(dashboardScript(directory, context.applicationInfo.sourceDir))
    return shell.check("mkdir -p '$directory' && chmod 700 '$directory'") &&
        shell.push(script, "$directory/start.sh") &&
        shell.check("chmod 700 '$directory/start.sh'; " +
            "nohup sh '$directory/start.sh' </dev/null >/dev/null 2>&1 &")
}

internal fun dashboardScript(directory: String, apkPath: String): String = """
    #!/system/bin/sh
    umask 077
    directory='${directory.replace("'", "'\"'\"'")}'
    apk='${apkPath.replace("'", "'\"'\"'")}'
    failures=0
    while true; do
        resolved=${'$'}(pm path com.strike 2>/dev/null | grep '/base.apk${'$'}' | head -n 1 | sed 's/^package://')
        if [ -n "${'$'}resolved" ] && [ -f "${'$'}resolved" ]; then apk="${'$'}resolved"; fi
        if [ -f "${'$'}apk" ]; then
            started=${'$'}(date +%s)
            CLASSPATH="${'$'}apk" app_process /system/bin --nice-name=strike_dashboard com.strike.server.DashboardDaemon "${'$'}directory" >> "${'$'}directory/daemon.log" 2>&1
            code=${'$'}?
            [ "${'$'}code" -eq 0 ] && exit 0
            [ "${'$'}code" -eq 3 ] && exit 0
            elapsed=${'$'}(( ${'$'}(date +%s) - started ))
            if [ "${'$'}elapsed" -ge 300 ] || [ "${'$'}code" -eq 42 ]; then failures=0; fi
        else
            packages=${'$'}(pm list packages com.strike 2>/dev/null)
            if [ "${'$'}?" -eq 0 ] && ! printf '%s\n' "${'$'}packages" | grep -Fx 'package:com.strike' >/dev/null; then exit 0; fi
        fi
        if [ "${'$'}failures" -lt 20 ]; then failures=${'$'}((failures + 1)); fi
        sleep ${'$'}((failures * 3))
    done
""".trimIndent() + "\n"
