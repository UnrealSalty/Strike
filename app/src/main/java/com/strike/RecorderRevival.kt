package com.strike

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.strike.core.Logs

class RecorderRevival : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATE -> if (intent.hasExtra("enabled")) {
                setEnabled(context, intent.getBooleanExtra("enabled", false), "shell broadcast")
            }
            ACTION_WAKE -> if (alarm(context).fired()) {
                recover(context)
            } else {
                BootDiagnostics.record("recorder revival alarm ignored: disabled")
            }
        }
    }

    companion object {
        const val ACTION_STATE = "com.strike.RECORDER_REVIVAL_STATE"
        const val ACTION_WAKE = "com.strike.RECORDER_REVIVAL_WAKE"
        private const val INTERVAL_MS = 300_000L
        private var current: RevivalAlarm? = null

        @Synchronized
        fun setEnabled(context: Context, enabled: Boolean, source: String = "recorder") {
            val changed = isEnabled(context) != enabled
            alarm(context).setEnabled(enabled)
            if (changed) BootDiagnostics.record("recorder revival enabled=$enabled source=$source")
            if (!enabled) context.stopService(Intent(context, RecorderRecoveryService::class.java))
        }

        internal fun revision(context: Context): Long = alarm(context).revision()

        @Synchronized
        internal fun reconcile(context: Context, enabled: Boolean, expectedRevision: Long) {
            val changed = isEnabled(context) != enabled
            if (!alarm(context).reconcile(enabled, expectedRevision)) return
            if (changed) BootDiagnostics.record("recorder revival enabled=$enabled source=dashboard")
            if (!enabled) context.stopService(Intent(context, RecorderRecoveryService::class.java))
        }

        fun isEnabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)

        @Synchronized
        fun resume(context: Context) {
            try {
                alarm(context).restore()
            } catch (e: RuntimeException) {
                BootDiagnostics.record("recorder revival alarm restore failed: ${e.javaClass.simpleName}")
                Logs.w("Recorder", "Could not restore recorder recovery alarm", e)
            }
            if (!isEnabled(context)) RecorderRevivalJob.cancel(context)
        }

        internal fun recover(context: Context) {
            resume(context)
            if (!isEnabled(context)) {
                BootDiagnostics.record("recorder revival skipped: disabled")
                return
            }
            if (!canRecoverAfterBoot(context)) {
                BootDiagnostics.record("recorder revival skipped: access not ready")
                return
            }
            try {
                context.startForegroundService(Intent(context, RecorderRecoveryService::class.java))
            } catch (e: RuntimeException) {
                BootDiagnostics.record("recorder revival could not start: ${e.javaClass.simpleName}")
                Logs.w("Recorder", "Could not start recorder recovery", e)
            }
        }

        @Synchronized
        private fun alarm(context: Context): RevivalAlarm {
            current?.let { return it }
            val saved = preferences(context)
            val manager = context.getSystemService(AlarmManager::class.java)
            val operation = PendingIntent.getBroadcast(context, 0,
                Intent(context, RecorderRevival::class.java).setAction(ACTION_WAKE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return RevivalAlarm(
                { saved.getBoolean("enabled", false) },
                { enabled -> check(saved.edit().putBoolean("enabled", enabled).commit()) {
                    "Could not save recorder recovery state"
                } },
                {
                    try {
                        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            SystemClock.elapsedRealtime() + INTERVAL_MS, operation)
                    } finally {
                        RecorderRevivalJob.schedule(context)
                    }
                },
                {
                    try { manager.cancel(operation) }
                    finally { RecorderRevivalJob.cancel(context) }
                }
            ).also { current = it }
        }

        private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("recorder-revival", Context.MODE_PRIVATE)
    }
}
