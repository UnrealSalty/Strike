package com.strike.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootHandoffTest {
    private var nowMs = 0L
    private var active = true
    private val starts = mutableListOf<Boolean>()

    @Test fun aPendingCameraYieldsBetweenProbesAndMaintenanceStartsOnlyOnce() {
        val ready = awaitBootRecovery({ active }, { start ->
            starts += start
            nowMs >= 1_000L
        }, { nowMs }, { nowMs += it })
        assertTrue(ready)
        assertEquals(listOf(true, false, false), starts)
        assertEquals(1_000L, nowMs)
    }

    @Test fun failedReadinessFinishesWithinOneBoundedAttempt() {
        val ready = awaitBootRecovery({ active }, { start ->
            starts += start
            false
        }, { nowMs }, { nowMs += it })
        assertFalse(ready)
        assertEquals(30_000L, nowMs)
        assertEquals(1, starts.count { it })
    }

    @Test fun timeSpentInAProbeCountsTowardTheDeadline() {
        val ready = awaitBootRecovery({ active }, {
            nowMs += 30_000L
            false
        }, { nowMs }, { throw AssertionError("Deadline already reached") })
        assertFalse(ready)
        assertEquals(30_000L, nowMs)
    }

    @Test fun cancellationBeforeStartingDoesNotContactTheDaemon() {
        active = false
        assertFalse(awaitBootRecovery({ active }, {
            throw AssertionError("Cancelled startup must not hand off")
        }, { nowMs }, { nowMs += it }))
    }

    @Test fun cancellationBetweenProbesPreventsFurtherRequests() {
        assertFalse(awaitBootRecovery({ active }, { start ->
            starts += start
            false
        }, { nowMs }, { active = false; nowMs += it }))
        assertEquals(listOf(true), starts)
    }

    @Test fun cancellationDuringTheFinalProbeCannotReportSuccess() {
        assertFalse(awaitBootRecovery({ active }, {
            active = false
            true
        }, { nowMs }, { nowMs += it }))
    }
}
