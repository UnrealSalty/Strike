package com.strike.daemon

private const val FIRST_REVIVE_MS = 30_000L
private const val LONGEST_REVIVE_MS = 8 * 60_000L
private const val REMOUNT_AFTER_MS = 10_000L

internal enum class CardStep { NONE, REVIVE, REMOUNT }

// The card reader hangs off the USB rail, which drops when the car lets the MCU sleep.
internal class CardOutage {
    var downSinceMs: Long? = null
        private set
    val isDown: Boolean get() = downSinceMs != null

    private var waitMs = FIRST_REVIVE_MS
    private var reviveAtMs = 0L
    private var remountAtMs: Long? = null

    fun observe(answered: Boolean, nowMs: Long): CardStep {
        if (answered) {
            downSinceMs = null
            waitMs = FIRST_REVIVE_MS
            remountAtMs = null
            return CardStep.NONE
        }
        if (downSinceMs == null) {
            downSinceMs = nowMs
            reviveAtMs = nowMs + FIRST_REVIVE_MS
        }
        val remountAt = remountAtMs
        if (remountAt != null) {
            if (nowMs < remountAt) return CardStep.NONE
            remountAtMs = null
            return CardStep.REMOUNT
        }
        if (nowMs < reviveAtMs) return CardStep.NONE
        remountAtMs = nowMs + REMOUNT_AFTER_MS
        waitMs = minOf(waitMs * 2, LONGEST_REVIVE_MS)
        reviveAtMs = nowMs + waitMs
        return CardStep.REVIVE
    }
}
