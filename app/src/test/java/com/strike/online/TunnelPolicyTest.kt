package com.strike.online

import com.strike.vehicle.VehicleSnapshot
import org.junit.Assert.*
import org.junit.Test

internal fun car(acc: Boolean?, locked: Boolean? = null, gear: String? = "P") =
    VehicleSnapshot(null, null, null, gear, acc, locked)

class TunnelPolicyTest {
    @Test fun alwaysModeDoesNotNeedVehicleSignals() {
        assertNull(tunnelWaiting("always", null))
        assertNull(tunnelWaiting("always", car(true, true, "D")))
    }

    @Test fun parkedModesNeverStartWithUnknownIgnitionOrDrivingGear() {
        for (mode in listOf("off", "lock")) {
            assertNotNull(tunnelWaiting(mode, null))
            assertNotNull(tunnelWaiting(mode, car(null, true)))
            assertNotNull(tunnelWaiting(mode, car(true, true)))
            assertNotNull(tunnelWaiting(mode, car(false, true, "D")))
        }
    }

    @Test fun onLockRequiresAConfirmedLockAndStopsOnUnlock() {
        assertNotNull(tunnelWaiting("lock", car(false, null)))
        assertNotNull(tunnelWaiting("lock", car(false, false)))
        assertNull(tunnelWaiting("lock", car(false, true)))
        assertNull(tunnelWaiting("off", car(false, false)))
    }

    @Test fun shortCrashesBackOffAndStopAfterFiveAttempts() {
        val retry = TunnelRetry()
        val delays = listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L)
        for ((index, delay) in delays.withIndex()) {
            retry.failed(100_000L, 1_000L)
            assertEquals(100_000L + delay, retry.atMs)
            assertEquals(index == 4, retry.exhausted)
        }
        retry.reset()
        assertFalse(retry.exhausted)
        assertEquals(0L, retry.atMs)
    }

    @Test fun aHealthyRunRestoresTheRetryBudget() {
        val retry = TunnelRetry()
        repeat(4) { retry.failed(0L, 1_000L) }
        retry.failed(500_000L, 300_000L)
        assertEquals(1, retry.failures)
        assertEquals(505_000L, retry.atMs)
    }
}
