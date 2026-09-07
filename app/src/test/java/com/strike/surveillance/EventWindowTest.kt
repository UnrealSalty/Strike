package com.strike.surveillance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventWindowTest {

    @Test
    fun aSlowEncoderStillGetsATailAfterItsClipStarts() {
        assertTrue(eventInProgress(triggeredUntilMs = 20_000L, clipStartedAtMs = 25_000L, nowMs = 26_000L))
        assertTrue(eventInProgress(triggeredUntilMs = 20_000L, clipStartedAtMs = 25_000L, nowMs = 44_999L))
        assertFalse(eventInProgress(triggeredUntilMs = 20_000L, clipStartedAtMs = 25_000L, nowMs = 45_000L))
    }

    @Test
    fun laterSightingsExtendTheRunningClip() {
        assertTrue(eventInProgress(triggeredUntilMs = 70_000L, clipStartedAtMs = 25_000L, nowMs = 69_000L))
        assertFalse(eventInProgress(triggeredUntilMs = 70_000L, clipStartedAtMs = 25_000L, nowMs = 70_000L))
    }

    @Test
    fun anExpiredSightingDoesNotCreateARecordingWindowWithoutAClip() {
        assertFalse(eventInProgress(triggeredUntilMs = 20_000L, clipStartedAtMs = 0L, nowMs = 21_000L))
        assertFalse(eventInProgress(triggeredUntilMs = 0L, clipStartedAtMs = 0L, nowMs = 1_000L))
    }
}
