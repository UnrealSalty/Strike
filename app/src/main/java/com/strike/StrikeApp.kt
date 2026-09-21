package com.strike

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import com.strike.core.Crashes
import com.strike.core.Logs
import com.strike.daemon.Shell
import com.strike.recording.Triggers
import com.strike.server.DashboardClient
import java.io.File

class StrikeApp : Application() {
    private lateinit var dashboardClient: DashboardClient
    private lateinit var setupRestart: SetupRestart
    private var earlyDashboard: DashboardClient? = null
    private var started = false

    internal val dashboard: DashboardClient
        get() { startUnlocked(); return dashboardClient }
    internal val setup: SetupRestart
        get() { startUnlocked(); return setupRestart }

    override fun onCreate() {
        super.onCreate()
        if (getSystemService(UserManager::class.java).isUserUnlocked) startUnlocked()
    }

    internal fun restoreAfterBoot(active: () -> Boolean, completed: (Boolean) -> Unit) {
        val client = if (getSystemService(UserManager::class.java).isUserUnlocked) dashboard else {
            earlyDashboard ?: createDeviceProtectedStorageContext().let { context ->
                DashboardClient(context, Shell(context)).also { earlyDashboard = it }
            }
        }
        client.restoreAfterBoot(active, completed)
    }

    @Synchronized
    private fun startUnlocked() {
        if (started) return
        check(getSystemService(UserManager::class.java).isUserUnlocked)
        Crashes.watch(filesDir)
        val main = Handler(Looper.getMainLooper())
        val shell = Shell(this) {
            main.post {
                dashboard.authorised()
                setup.shellAuthorised()
            }
        }
        setupRestart = SetupRestart(
            File(filesDir, "setup.pending"),
            !File(filesDir, "adbkey").isFile || !File(filesDir, "adbkey.pub").isFile,
            { shell.retry() }
        ) {
            Thread({
                Logs.d("Shell", "Restarting Strike after USB debugging approval")
                val started = shell.check("nohup sh -c 'am force-stop com.strike; " +
                    "am start -n com.strike/.MainActivity' </dev/null >/dev/null 2>&1 &")
                if (!started) Logs.w("Shell", "Setup is complete. Close and reopen Strike")
            }, "setup-restart").start()
        }
        dashboardClient = DashboardClient(this, shell)
        started = true
        Triggers(this, shell).start()
    }
}
