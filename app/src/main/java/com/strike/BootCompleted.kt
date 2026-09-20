package com.strike

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.strike.core.Logs
import java.io.File

private const val BOOT_JOB_ID = 1

class BootCompleted : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !canRecoverAfterBoot(context.filesDir)) return
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if (scheduler.getPendingJob(BOOT_JOB_ID) != null) return
        val job = JobInfo.Builder(BOOT_JOB_ID, ComponentName(context, BootRecovery::class.java))
            .setOverrideDeadline(0L)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        if (scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) {
            Logs.d("Boot", "Restoring Strike after head unit restart")
        } else {
            Logs.w("Boot", "Could not schedule startup after head unit restart")
        }
    }
}

internal fun canRecoverAfterBoot(directory: File): Boolean =
    File(directory, "dashboard.migrated").isFile &&
        File(directory, "adbkey").isFile && File(directory, "adbkey.pub").isFile &&
        !File(directory, "setup.pending").exists()
