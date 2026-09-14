package com.strike.recording

internal class AudioClock(private val startedAtUs: Long, private val sampleRate: Int) {
    private var samplesRead = 0L

    // Rejected encoder input still consumed microphone time.
    fun read(bytes: Int): Long {
        val timeUs = startedAtUs + samplesRead * 1_000_000L / sampleRate
        samplesRead += bytes / 2
        return timeUs
    }
}
