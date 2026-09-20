package com.strike

import android.app.job.JobParameters
import android.app.job.JobService

class BootRecovery : JobService() {
    @Volatile private var request = 0L

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!canRecoverAfterBoot(filesDir)) return false
        recover((application as StrikeApp).dashboard::restoreAfterBoot) { retry ->
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
        request++
        return true
    }

    override fun onDestroy() {
        request++
        super.onDestroy()
    }
}
