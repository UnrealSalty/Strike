package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioClockTest {

    @Test
    fun aRejectedBufferLeavesItsTimeBeforeTheNextAcceptedBuffer() {
        val clock = AudioClock(1_000_000L, 48_000)
        val accepted = ArrayList<Long>()
        val available = listOf(false, true)
        for (hasInputBuffer in available) {
            val timeUs = clock.read(960)
            if (hasInputBuffer) accepted.add(timeUs)
        }
        assertEquals(listOf(1_010_000L), accepted)
    }

    @Test
    fun rejectingTheTailOfAReadDoesNotShortenTheNextTimestamp() {
        val clock = AudioClock(1_000_000L, 48_000)
        val firstReadUs = clock.read(4096)
        val acceptedFirstChunkUs = firstReadUs
        val nextReadUs = clock.read(2048)
        assertEquals(42_666L, nextReadUs - acceptedFirstChunkUs)
    }

    @Test
    fun roundingEachBufferDoesNotAccumulateClockDrift() {
        val clock = AudioClock(0L, 48_000)
        repeat(3) { clock.read(2048) }
        assertEquals(64_000L, clock.read(2048))
    }
}
