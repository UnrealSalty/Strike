package com.strike.camera

internal class FramePacing(frameRateFps: Int) {
    private val intervalNs = 1_000_000_000L / frameRateFps
    private var nextFrameAtNs = Long.MIN_VALUE
    private var lastSeenAtNs = Long.MIN_VALUE

    fun take(timestampNs: Long): Boolean {
        if (timestampNs <= lastSeenAtNs) return false
        lastSeenAtNs = timestampNs
        if (nextFrameAtNs == Long.MIN_VALUE || timestampNs - nextFrameAtNs >= intervalNs) {
            nextFrameAtNs = timestampNs + intervalNs
            return true
        }
        if (timestampNs < nextFrameAtNs) return false
        nextFrameAtNs += intervalNs
        return true
    }
}
