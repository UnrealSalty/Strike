package com.strike.recording

import android.media.MediaCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRollTest {

    private fun frame(atMs: Long, key: Boolean = false) = Sample(
        ByteArray(10), atMs * 1000L, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0, false
    )

    private fun fill(ring: PreRoll, untilMs: Long) {
        var atMs = 0L
        while (atMs <= untilMs) {
            ring.add(frame(atMs, key = atMs % 1000L == 0L))
            atMs += 100L
        }
    }

    @Test
    fun keepsTheNewestFootageUpToTheBudget() {
        val ring = PreRoll(7_000L, 1_000_000L)
        fill(ring, 15_000L)
        assertEquals(7_000L, ring.spanMs)
        assertEquals(15_000L, ring.snapshot().last().timeUs / 1000L)
    }

    @Test
    fun theOldestSampleKeptIsAlwaysAKeyframe() {
        val ring = PreRoll(7_000L, 1_000_000L)
        for (atMs in 0L..15_000L step 250L) {
            ring.add(frame(atMs, key = atMs % 1000L == 0L))
            assertTrue(keyFrame(ring.snapshot().first()))
        }
    }

    @Test
    fun theByteBudgetBoundsTheBufferWhenTimestampsStopAdvancing() {
        val ring = PreRoll(7_000L, 250L)
        for (at in 0 until 100) ring.add(frame(0L, key = at % 10 == 0))
        assertTrue(ring.count <= 25)
        assertTrue(keyFrame(ring.snapshot().first()))
    }

    @Test
    fun snapshotKeepsTheOrderItWasWrittenInAndClearEmptiesIt() {
        val ring = PreRoll(7_000L, 1_000_000L)
        fill(ring, 3_000L)
        val flushed = ring.snapshot()
        assertEquals(flushed.sortedBy { it.timeUs }.map { it.timeUs }, flushed.map { it.timeUs })
        ring.clear()
        assertEquals(0, ring.count)
        assertEquals(0L, ring.spanMs)
    }
}
