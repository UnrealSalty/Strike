package com.strike.daemon

private const val STARTUP_MS = 120_000L
private const val RECOVERY_MS = 90_000L
private const val SUPERVISOR_MS = 60_000L

internal class CaptureHealth {
    private var watching = false
    private var hasOutput = false
    private var output = 0L
    private var progressedAtMs = 0L

    fun check(expected: Boolean, supervisorAtMs: Long, outputAtMs: Long, nowMs: Long): String? {
        if (!expected) {
            watching = false
            hasOutput = false
            output = 0L
            return null
        }
        if (!watching) {
            watching = true
            progressedAtMs = nowMs
        }
        // Output uses elapsed time; deadlines use uptime so device sleep is not a stalled worker.
        if (outputAtMs > 0L && outputAtMs != output) {
            output = outputAtMs
            hasOutput = true
            progressedAtMs = nowMs
        }
        val controlBudgetMs = if (hasOutput) SUPERVISOR_MS else STARTUP_MS
        if (nowMs - supervisorAtMs >= controlBudgetMs) return "recording control stopped responding"
        val outputBudgetMs = if (hasOutput) RECOVERY_MS else STARTUP_MS
        if (nowMs - progressedAtMs >= outputBudgetMs) return "recording recovery produced no video"
        return null
    }
}
