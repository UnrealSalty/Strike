package com.strike.recording

private const val SPLICE_DEADLINE_MS = 3_000L

internal class ClipRotation {
    private var nextId = 0L
    private var pendingId = 0L
    private var askedAtMs = 0L
    private var boundaryEmitted = false

    @Synchronized
    fun request(nowMs: Long): Boolean {
        if (pendingId != 0L) return false
        pendingId = ++nextId
        askedAtMs = nowMs
        boundaryEmitted = false
        return true
    }

    @Synchronized
    fun boundary(keyFrame: Boolean, nowMs: Long): Long {
        if (pendingId == 0L || boundaryEmitted) return 0L
        if (!keyFrame && nowMs - askedAtMs < SPLICE_DEADLINE_MS) return 0L
        boundaryEmitted = true
        return pendingId
    }

    @Synchronized
    fun isPending(id: Long): Boolean = id != 0L && id == pendingId

    @Synchronized
    fun complete() {
        pendingId = 0L
    }
}
