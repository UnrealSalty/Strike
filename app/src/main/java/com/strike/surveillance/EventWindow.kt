package com.strike.surveillance

internal fun eventInProgress(triggeredUntilMs: Long, clipStartedAtMs: Long, nowMs: Long): Boolean {
    if (triggeredUntilMs > nowMs) return true
    return clipStartedAtMs > 0L && nowMs < clipStartedAtMs + EVENT_TAIL_MS
}
