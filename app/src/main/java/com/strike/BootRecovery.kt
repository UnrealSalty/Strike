package com.strike

import android.app.job.JobParameters
import android.app.job.JobService

class BootRecovery : JobService() {
    @Volatile private var request = 0L
    private var recovering = false

    override fun onStartJob(params: JobParameters?): Boolean {
        BootDiagnostics.environment(this, "job started")
        if (!canRecoverAfterBoot(this)) {
            BootDiagnostics.record("job ended: access not ready")
            return false
        }
        recovering = true
        recover((application as StrikeApp)::restoreAfterBoot) { retry ->
            recovering = false
            BootDiagnostics.record("job finished retry=$retry")
            jobFinished(params, retry)
        }
        return true
    }

    internal fun recover(restore: (() -> Boolean, (Boolean) -> Unit) -> Unit, finished: (Boolean) -> Unit) {
        val current = ++request
        restore({ current == request }) { ready ->
            if (current != request) return@restore
            request++
            finished(!ready)
        }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        val reason = if (android.os.Build.VERSION.SDK_INT >= 31) params?.stopReason else null
        BootDiagnostics.record("job stopped reason=${reason ?: "unavailable"}; retry requested")
        recovering = false
        request++
        return true
    }

    override fun onDestroy() {
        if (recovering) BootDiagnostics.record("job service destroyed during recovery")
        recovering = false
        request++
        super.onDestroy()
    }
}
