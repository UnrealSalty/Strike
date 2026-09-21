package com.strike.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedMaintenanceTest {
    private var elapsedMs = 0L
    private val maintenance = ParkedMaintenance { elapsedMs }

    private fun performedAll() {
        maintenance.didActivity()
        maintenance.didVote()
        maintenance.didWake()
    }

    @Test fun activityVotesAndWakeKeepTheirExistingIntervals() {
        performedAll()
        elapsedMs = 9_999L
        assertFalse(maintenance.activityDue)
        elapsedMs++
        assertTrue(maintenance.activityDue)
        assertFalse(maintenance.voteDue)
        assertFalse(maintenance.wakeDue)
        elapsedMs = 299_999L
        assertFalse(maintenance.voteDue)
        elapsedMs++
        assertTrue(maintenance.voteDue)
        assertFalse(maintenance.wakeDue)
        elapsedMs = 479_999L
        assertFalse(maintenance.wakeDue)
        elapsedMs++
        assertTrue(maintenance.wakeDue)
    }

    @Test fun activityCannotPostponeTheRailVoteOrMcuWake() {
        performedAll()
        repeat(30) {
            elapsedMs += 10_000L
            assertTrue(maintenance.activityDue)
            maintenance.didActivity()
        }
        assertTrue(maintenance.voteDue)
        maintenance.didVote()
        assertFalse(maintenance.voteDue)
        elapsedMs = 480_000L
        assertTrue(maintenance.wakeDue)
        assertFalse(maintenance.voteDue)
    }

    @Test fun lateMaintenanceDoesNotReplayMissedTicksInABurst() {
        performedAll()
        elapsedMs += 16 * 3_600_000L
        assertTrue(maintenance.activityDue)
        assertTrue(maintenance.voteDue)
        assertTrue(maintenance.wakeDue)
        performedAll()
        repeat(100) {
            assertFalse(maintenance.activityDue)
            assertFalse(maintenance.voteDue)
            assertFalse(maintenance.wakeDue)
        }
        elapsedMs += 10_000L
        assertTrue(maintenance.activityDue)
        assertFalse(maintenance.voteDue)
        assertFalse(maintenance.wakeDue)
    }

    @Test fun restartingParkedMaintenanceUsesTheNewSessionTimes() {
        elapsedMs = 20 * 24 * 3_600_000L
        performedAll()
        elapsedMs += 300_000L
        assertTrue(maintenance.voteDue)
        performedAll()
        elapsedMs += 179_999L
        assertTrue(maintenance.activityDue)
        assertFalse(maintenance.voteDue)
        assertFalse(maintenance.wakeDue)
        elapsedMs += 300_001L
        assertTrue(maintenance.wakeDue)
    }
}
