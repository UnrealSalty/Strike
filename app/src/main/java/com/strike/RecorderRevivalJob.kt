package com.strike

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.strike.core.Logs

class RecorderRevivalJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        BootDiagnostics.record("recorder backup job started")
        RecorderRevival.recover(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        internal const val JOB_ID = 2
        private const val INTERVAL_MS = 900_000L

        internal fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java)
                if (scheduler.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, RecorderRevivalJob::class.java))
                    .setPeriodic(INTERVAL_MS)
                    .setPersisted(true)
                    .build()
                val result = scheduler.schedule(job)
                BootDiagnostics.record("recorder backup job schedule result=$result")
                if (result != JobScheduler.RESULT_SUCCESS) {
                    Logs.w("Recorder", "Could not schedule recorder recovery backup")
                }
            } catch (e: RuntimeException) {
                BootDiagnostics.record("recorder backup job schedule failed: ${e.javaClass.simpleName}")
                Logs.w("Recorder", "Could not schedule recorder recovery backup", e)
            }
        }

        internal fun cancel(context: Context) {
            try {
                context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
            } catch (e: RuntimeException) {
                BootDiagnostics.record("recorder backup job cancellation failed: ${e.javaClass.simpleName}")
                Logs.w("Recorder", "Could not cancel recorder recovery backup", e)
            }
        }
    }
}
