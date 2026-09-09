package com.strike.online

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.strike.core.Logs
import com.strike.daemon.Shell

internal class OnlinePower(private val context: Context, private val shell: Shell) {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    fun prepare(): Boolean {
        if (!shell.isAuthorised()) return false
        val apk = context.applicationInfo.sourceDir.replace("'", "'\"'\"'")
        val output = shell.read("CLASSPATH='$apk' timeout -s KILL 10 app_process " +
            "/system/bin com.strike.online.ParkedAccess")
        val status = output?.lineSequence()?.firstOrNull { it.startsWith("parked-access ") }
        if (status == null) {
            Logs.w("Online", "Could not set up parked access. Reopen Strike to retry")
        } else {
            if (status.contains("android=allowed") && !status.contains("byd=rejected")) {
                Logs.d("Online", status)
            } else {
                Logs.w("Online", status)
            }
        }
        return true
    }

    @Synchronized
    fun hold(wanted: Boolean) {
        if (!wanted) {
            wifi?.let { if (it.isHeld) it.release() }
            cpu?.let { if (it.isHeld) it.release() }
            return
        }
        if (cpu == null) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strike:online").also {
                it.setReferenceCounted(false)
            }
        }
        if (wifi == null) {
            val wireless = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifi = wireless?.createWifiLock(WifiManager.WIFI_MODE_FULL, "strike:online")?.also {
                it.setReferenceCounted(false)
            }
        }
        cpu?.let { if (!it.isHeld) it.acquire() }
        wifi?.let { if (!it.isHeld) it.acquire() }
    }
}
