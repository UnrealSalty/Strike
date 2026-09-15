package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureHealthTest {
    @Test
    fun anInitiallyDisabledRecorderDoesNotNeedHeartbeatOrOutput() {
        val health = CaptureHealth()
        assertNull(health.check(false, 0L, 0L, 1_000_000L))
        assertNull(health.check(false, 0L, 0L, 10_000_000L))
    }

    @Test
    fun enablingAfterLongIdleStartsAFreshOutputGrace() {
        val health = CaptureHealth()
        assertNull(health.check(false, 0L, 999_000L, 1_000_000L))
        assertNull(health.check(true, 2_000_000L, 0L, 2_000_000L))
        assertNull(health.check(true, 2_119_999L, 0L, 2_119_999L))
        assertEquals("recording recovery produced no video", health.check(true, 2_120_000L, 0L, 2_120_000L))
    }
    @Test
    fun startupHasTwoMinutesToProduceVideo() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 0L, 1_000L))
        assertNull(health.check(true, 120_999L, 0L, 120_999L))
        assertEquals("recording recovery produced no video", health.check(true, 121_000L, 0L, 121_000L))
    }

    @Test
    fun aControlLoopThatNeverStartsUsesTheStartupGrace() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 0L, 1_000L))
        assertNull(health.check(true, 1_000L, 0L, 120_999L))
        assertEquals("recording control stopped responding", health.check(true, 1_000L, 0L, 121_000L))
    }

    @Test
    fun anEstablishedRecordingGetsNinetySecondsToRecoverOutput() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 1_000L, 1_000L))
        assertNull(health.check(true, 90_999L, 1_000L, 90_999L))
        assertEquals("recording recovery produced no video", health.check(true, 91_000L, 1_000L, 91_000L))
    }

    @Test
    fun videoCannotHideAControlLoopThatHasBeenStuckForAMinute() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 1_000L, 1_000L))
        assertNull(health.check(true, 1_000L, 60_999L, 60_999L))
        assertEquals("recording control stopped responding", health.check(true, 1_000L, 61_000L, 61_000L))
    }

    @Test
    fun repeatedlyReplacingAnEncoderWithoutOutputCannotResetRecovery() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 1_000L, 1_000L))
        assertNull(health.check(true, 30_000L, 0L, 30_000L))
        assertNull(health.check(true, 60_000L, 0L, 60_000L))
        assertEquals("recording recovery produced no video", health.check(true, 91_000L, 0L, 91_000L))
    }

    @Test
    fun anUnchangedOutputTimestampDoesNotHealAStalledRecording() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 500_000L, 1_000L))
        assertNull(health.check(true, 60_000L, 500_000L, 60_000L))
        assertEquals("recording recovery produced no video", health.check(true, 91_000L, 500_000L, 91_000L))
    }

    @Test
    fun observedVideoProgressRestartsTheRecoveryBudget() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 10_000L, 1_000L))
        assertNull(health.check(true, 80_000L, 11_000L, 80_000L))
        assertNull(health.check(true, 169_999L, 11_000L, 169_999L))
        assertEquals("recording recovery produced no video", health.check(true, 170_000L, 11_000L, 170_000L))
    }

    @Test
    fun intentionalOffClearsTheOldFailureAndRestoresStartupGrace() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 1_000L, 1_000L))
        assertNull(health.check(false, 1_000L, 1_000L, 900_000L))
        assertNull(health.check(true, 901_000L, 0L, 901_000L))
        assertNull(health.check(true, 1_020_999L, 0L, 1_020_999L))
        assertEquals("recording recovery produced no video", health.check(true, 1_021_000L, 0L, 1_021_000L))
    }

    @Test
    fun elapsedOutputAndAwakeDeadlinesDoNotHaveToShareAnOrigin() {
        val health = CaptureHealth()
        assertNull(health.check(true, 1_000L, 1_000_000L, 1_000L))
        assertNull(health.check(true, 2_000L, 1_500_000L, 2_000L))
        assertNull(health.check(true, 91_999L, 1_500_000L, 91_999L))
        assertEquals("recording recovery produced no video", health.check(true, 92_000L, 1_500_000L, 92_000L))
    }

    @Test
    fun aDayBoundaryDoesNotRestartAHealthyRecorder() {
        val health = CaptureHealth()
        val dayBoundary = 24 * 60 * 60 * 1_000L
        for (offset in -60_000L..120_000L step 30_000L) {
            val now = dayBoundary + offset
            assertNull(health.check(true, now, now - 67L, now))
        }
    }
}
