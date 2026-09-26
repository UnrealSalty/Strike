package com.strike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class RecorderRecoveryService : Service() {
    private lateinit var main: Handler
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var request = 0L
    @Volatile private var running = false
    private var timeout: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        main = Handler(Looper.getMainLooper())
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            "Recorder recovery", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_online)
            .setContentTitle("Strike")
            .setContentText("Checking recorder")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RecorderRevival.isEnabled(this) || !canRecoverAfterBoot(this)) {
            finish("not enabled or access not ready")
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val deadlineMs = SystemClock.elapsedRealtime() + TIMEOUT_MS
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Strike:RecorderRecovery")
            .apply { acquire(TIMEOUT_MS) }
        timeout = Runnable { finish("timed out") }.also { main.postDelayed(it, TIMEOUT_MS) }
        recover((application as StrikeApp)::restoreAfterBoot,
            { RecorderRevival.isEnabled(this) && SystemClock.elapsedRealtime() < deadlineMs }) { ready ->
            finish(if (ready) "ready" else "not ready")
        }
        return START_NOT_STICKY
    }

    internal fun recover(restore: (() -> Boolean, (Boolean) -> Unit) -> Unit,
                         enabled: () -> Boolean, completed: (Boolean) -> Unit): Boolean {
        if (running || !enabled()) return false
        running = true
        val current = ++request
        restore({ running && request == current && enabled() }) { ready ->
            if (!running || request != current) return@restore
            cancelAttempt()
            completed(ready)
        }
        return true
    }

    internal fun cancelAttempt() {
        running = false
        request++
    }

    private fun finish(outcome: String) {
        cancelAttempt()
        BootDiagnostics.record("recorder revival $outcome")
        release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun release() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }

    override fun onDestroy() {
        cancelAttempt()
        release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "recorder-recovery"
        private const val NOTIFICATION_ID = 2
        private const val TIMEOUT_MS = 120_000L
    }
}
