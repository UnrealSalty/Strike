package com.strike.core

import com.strike.core.DiLink5VoltageGuard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiLink5VoltageGuardTest {
    @Test
    fun activationNeedsAValidVoltageAboveTheCutoff() {
        val guard = DiLink5VoltageGuard { 0L }
        for (volts in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 5.99, 17.01)) {
            assertEquals(State.WAITING, guard.sample(volts))
        }
        assertEquals(State.WAITING, guard.sample(11.8))
        assertEquals(State.WAITING, guard.sample(6.0))
        assertFalse(guard.blocked)
        assertEquals(State.READY, guard.sample(17.0))
    }

    @Test
    fun aSampleAtUptimeZeroExpiresAtTwoMinutes() {
        var now = 0L
        val guard = DiLink5VoltageGuard { now }
        assertEquals(State.READY, guard.sample(12.5))
        now = 119_999L
        assertEquals(State.READY, guard.sample(null))
        now = 120_000L
        assertEquals(State.WAITING, guard.sample(Double.NaN))
        now = 150_000L
        assertEquals(State.WAITING, guard.sample(1_250.0))
        assertEquals(State.READY, guard.sample(12.5))
    }

    @Test
    fun threeLowReadingsLatchUntilResetEvenWhenTheVoltageRebounds() {
        var now = 0L
        val guard = DiLink5VoltageGuard { now }
        assertEquals(State.READY, guard.sample(12.5))
        now += 30_000L
        assertEquals(State.READY, guard.sample(11.8))
        now += 30_000L
        assertEquals(State.READY, guard.sample(11.7))
        now += 30_000L
        assertEquals(State.LOW, guard.sample(11.6))
        assertTrue(guard.blocked)
        now += 180_000L
        assertEquals(State.LOW, guard.sample(null))
        assertEquals(State.LOW, guard.sample(13.5))
        guard.reset()
        assertFalse(guard.blocked)
        assertEquals(State.WAITING, guard.sample(null))
        assertEquals(State.READY, guard.sample(12.5))
    }

    @Test
    fun missingAndInvalidReadingsDoNotEraseTheLowVoltageCount() {
        var now = 0L
        val guard = DiLink5VoltageGuard { now }
        assertEquals(State.READY, guard.sample(12.5))
        assertEquals(State.READY, guard.sample(11.7))
        now += 30_000L
        assertEquals(State.READY, guard.sample(null))
        assertEquals(State.READY, guard.sample(Double.NaN))
        assertEquals(State.READY, guard.sample(11.6))
        now += 120_000L
        assertEquals(State.WAITING, guard.sample(0.0))
        assertEquals(State.LOW, guard.sample(11.5))
    }

    @Test
    fun aHealthyReadingBreaksTheConsecutiveLowVoltageRun() {
        val guard = DiLink5VoltageGuard { 0L }
        assertEquals(State.READY, guard.sample(12.5))
        repeat(2) { assertEquals(State.READY, guard.sample(11.7)) }
        assertEquals(State.READY, guard.sample(11.81))
        repeat(2) { assertEquals(State.READY, guard.sample(11.7)) }
        assertFalse(guard.blocked)
        assertEquals(State.LOW, guard.sample(11.7))
    }

    @Test
    fun aPersistedCutoffCannotBeBypassedByRestartingTheGuard() {
        val guard = DiLink5VoltageGuard(initialBlocked = true) { 0L }
        assertTrue(guard.blocked)
        assertEquals(State.LOW, guard.sample(13.5))
        guard.reset()
        assertEquals(State.WAITING, guard.sample(11.8))
        assertEquals(State.READY, guard.sample(12.0))
    }

    @Test
    fun aNewParkedSessionCannotReuseThePreviousVoltageOrLowCount() {
        val guard = DiLink5VoltageGuard { 0L }
        assertEquals(State.READY, guard.sample(12.5))
        repeat(2) { assertEquals(State.READY, guard.sample(11.7)) }
        guard.reset()
        assertEquals(State.WAITING, guard.sample(null))
        assertEquals(State.WAITING, guard.sample(11.7))
        assertFalse(guard.blocked)
        assertEquals(State.READY, guard.sample(12.5))
    }
}
