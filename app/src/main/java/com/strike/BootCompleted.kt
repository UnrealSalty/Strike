package com.strike

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserManager
import com.strike.core.Logs
import java.io.File

private const val BOOT_JOB_ID = 1

class BootCompleted : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        BootDiagnostics.environment(context, "broadcast ${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) prepareBootAccess(context)
        if (!canRecoverAfterBoot(context)) {
            BootDiagnostics.record("startup skipped: access not ready")
            return
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.getPendingJob(BOOT_JOB_ID) != null) {
            BootDiagnostics.record("startup already scheduled")
            return
        }
        val job = JobInfo.Builder(BOOT_JOB_ID, ComponentName(context, BootRecovery::class.java))
            .setOverrideDeadline(0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val scheduled = scheduler.schedule(job)
        BootDiagnostics.record("startup schedule result=$scheduled")
        if (scheduled == JobScheduler.RESULT_SUCCESS) {
            val phase = if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) "before Android unlock" else "after Android unlock"
            Logs.d("Boot", "Restoring Strike $phase, boot uptime ${SystemClock.elapsedRealtime() / 1000}s")
        } else {
            Logs.w("Boot", "Could not schedule startup after head unit restart")
        }
    }
}

internal fun canRecoverAfterBoot(directory: File): Boolean =
    File(directory, "dashboard.migrated").isFile && File(directory, "dashboard.identity").isFile &&
        File(directory, "adbkey").isFile && File(directory, "adbkey.pub").isFile &&
        !File(directory, "setup.pending").exists()

internal fun canRecoverAfterBoot(context: Context): Boolean = canRecoverAfterBoot(
    if (context.getSystemService(UserManager::class.java).isUserUnlocked) context.filesDir
    else context.createDeviceProtectedStorageContext().filesDir
)
