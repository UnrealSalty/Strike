package com.strike.online

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.strike.daemon.DaemonClient
import com.strike.daemon.DaemonLog
import com.strike.daemon.PANEL_LOCK_PATH
import com.strike.daemon.PanelLease
import com.strike.daemon.ParkedPanel
import com.strike.daemon.ParkedRails
import java.io.File

internal class OnlinePower(private val context: Context) {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val panel = ParkedPanel("Online")
    private val panelLease = PanelLease(File(PANEL_LOCK_PATH), camera = false)
    private val camera = DaemonClient()
    private var wakePending = false
    private var wakeFailed = false

    @Synchronized
    fun hold(wanted: Boolean, parked: Boolean, stillWanted: () -> Boolean): Boolean {
        if (!wanted) {
            if (panelLease.isHeld) panel.wake()
            if (!releasePanel()) return false
            wakePending = false
            ParkedRails.release()
            wifi?.let { if (it.isHeld) it.release() }
            cpu?.let { if (it.isHeld) it.release() }
            return !ParkedRails.isHeld
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
        if (parked) {
            val wasHeld = ParkedRails.isHeld
            ParkedRails.hold(camera = false, stillWanted = stillWanted)
            if (!ParkedRails.isHeld) return false
            val refresh = ParkedRails.tick()
            if (!wasHeld || refresh) wakePending = true
            val cameraWaiting = panelLease.cameraWaiting
            if (cameraWaiting && panelLease.isHeld) {
                if (!releasePanel()) return false
                wakePending = true
            }
            if (!cameraWaiting && !panelLease.isHeld) wakePending = true
            if (wakePending && stillWanted()) {
                val accepted = if (panelLease.acquire()) {
                    val woke = ParkedRails.wakeAp()
                    if (stillWanted()) panel.darken()
                    if (!stillWanted()) {
                        panel.wake()
                        releasePanel()
                    }
                    woke
                } else {
                    camera.parkedWake()
                }
                if (accepted) {
                    DaemonLog.d("Online", "Parked head-unit wake requested")
                    wakePending = false
                    wakeFailed = false
                } else if (!wakeFailed) {
                    DaemonLog.w("Online", "Waiting for parked head-unit wake")
                    wakeFailed = true
                }
            }
        } else {
            if (panelLease.isHeld) panel.wake()
            if (!releasePanel()) return false
            wakePending = false
            ParkedRails.release()
        }
        return true
    }

    @Synchronized
    fun releasePanel(): Boolean {
        if (!panel.release()) return false
        panelLease.release()
        wakePending = true
        return true
    }
}
