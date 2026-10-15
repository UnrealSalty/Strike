package com.strike.daemon

private const val FIRST_REVIVE_MS = 30_000L
private const val REMOUNT_AFTER_MS = 10_000L
private const val REVIVES = 3

internal enum class CardStep { NONE, REVIVE, REMOUNT, REPLUG }

// The card reader hangs off the USB rail, which drops when the car lets the MCU sleep.
// A reader that hangs with power on only comes back when it is replugged or the head unit restarts.
internal class CardOutage {
    var downSinceMs: Long? = null
        private set
    val isDown: Boolean get() = downSinceMs != null
    var needsReplug = false
        private set

    private var waitMs = FIRST_REVIVE_MS
    private var reviveAtMs = 0L
    private var remountAtMs: Long? = null
    private var revives = 0

    fun observe(answered: Boolean, nowMs: Long): CardStep {
        if (answered) {
            downSinceMs = null
            needsReplug = false
            waitMs = FIRST_REVIVE_MS
            remountAtMs = null
            revives = 0
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
        if (needsReplug || nowMs < reviveAtMs) return CardStep.NONE
        if (revives == REVIVES) {
            needsReplug = true
            return CardStep.REPLUG
        }
        revives++
        remountAtMs = nowMs + REMOUNT_AFTER_MS
        waitMs *= 2
        reviveAtMs = nowMs + waitMs
        return CardStep.REVIVE
    }
}
