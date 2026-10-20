package com.strike.camera

internal class FramePacing(frameRateFps: Int) {
    private val intervalNs = 1_000_000_000L / frameRateFps
    // A camera running at the paced rate jitters; a frame this early still counts as on time.
    private val earlyNs = intervalNs / 4
    private var nextFrameAtNs = Long.MIN_VALUE
    private var lastSeenAtNs = Long.MIN_VALUE

    fun take(timestampNs: Long): Boolean {
        if (timestampNs <= lastSeenAtNs) return false
        lastSeenAtNs = timestampNs
        if (nextFrameAtNs == Long.MIN_VALUE || timestampNs - nextFrameAtNs >= intervalNs) {
            nextFrameAtNs = timestampNs + intervalNs
            return true
        }
        if (timestampNs < nextFrameAtNs - earlyNs) return false
        nextFrameAtNs += intervalNs
        return true
    }
}
