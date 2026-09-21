package com.strike.daemon

private const val ACTIVITY_MS = 10_000L
private const val VOTE_MS = 5 * 60_000L
private const val WAKE_MS = 8 * 60_000L

internal class ParkedMaintenance(private val nowMs: () -> Long) {
    private var activityAtMs = 0L
    private var voteAtMs = 0L
    private var wakeAtMs = 0L

    val activityDue: Boolean get() = nowMs() - activityAtMs >= ACTIVITY_MS
    val voteDue: Boolean get() = nowMs() - voteAtMs >= VOTE_MS
    val wakeDue: Boolean get() = nowMs() - wakeAtMs >= WAKE_MS

    fun didActivity() { activityAtMs = nowMs() }
    fun didVote() { voteAtMs = nowMs() }
    fun didWake() { wakeAtMs = nowMs() }
}
