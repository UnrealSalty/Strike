package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingTest {
    @Test
    fun fifteenAndThirtyFpsCamerasBothFeedTwelveLiveFramesPerSecond() {
        for (sourceFps in listOf(15, 30)) {
            val pacing = FramePacing(12)
            val accepted = (0 until sourceFps * 60).count { frame ->
                pacing.take(frame * 1_000_000_000L / sourceFps)
            }
            assertEquals("source $sourceFps fps", 720, accepted)
        }
    }

    @Test
    fun aCameraBelowTheLiveLimitKeepsEveryFrame() {
        for (sourceFps in listOf(4, 5, 10, 12)) {
            val pacing = FramePacing(12)
            repeat(sourceFps * 60) { frame ->
                assertTrue(pacing.take(frame * 1_000_000_000L / sourceFps))
            }
        }
    }

    @Test
    fun aLongPauseResumesImmediatelyWithoutCatchingUpMissedFrames() {
        val pacing = FramePacing(12)
        assertTrue(pacing.take(0L))
        assertTrue(pacing.take(10_000_000_000L))
        repeat(82) { offsetMs ->
            assertFalse(pacing.take(10_001_000_000L + offsetMs * 1_000_000L))
        }
        assertTrue(pacing.take(10_084_000_000L))
    }

    @Test
    fun duplicateOrOlderTimestampsCannotTriggerExtraFrames() {
        val pacing = FramePacing(12)
        assertTrue(pacing.take(1_000_000_000L))
        assertFalse(pacing.take(1_000_000_000L))
        assertFalse(pacing.take(900_000_000L))
        assertFalse(pacing.take(1_010_000_000L))
        assertTrue(pacing.take(1_084_000_000L))
    }

    @Test
    fun theMonotonicClockCanHaveANegativeOrigin() {
        val pacing = FramePacing(12)
        assertTrue(pacing.take(-1_000_000_000L))
        assertFalse(pacing.take(-990_000_000L))
        assertTrue(pacing.take(-916_000_000L))
    }
}
