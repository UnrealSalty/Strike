package com.strike.recording

import com.strike.vehicle.VehicleSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReadinessTest {
    @Test fun disabledSurveillanceDoesNotHoldBootOpenForVehicleSignals() {
        assertTrue(parkedStartupReady(false, "off", null, false))
    }

    @Test fun unknownPowerWaitsUnlessParkedWakeLockIsAlreadyHeld() {
        assertFalse(parkedStartupReady(true, "off", null, false))
        assertFalse(parkedStartupReady(true, "off", snapshot(null), false))
        assertTrue(parkedStartupReady(true, "off", null, true))
    }

    @Test fun parkedCaptureWaitsForActualWakeLockHandoff() {
        assertFalse(parkedStartupReady(true, "off", snapshot(false), false))
        assertTrue(parkedStartupReady(true, "off", snapshot(false), true))
    }

    @Test fun aRunningOrMovingCarDoesNotWaitForParkedPower() {
        assertTrue(parkedStartupReady(true, "off", snapshot(true), false))
        assertTrue(parkedStartupReady(true, "off", snapshot(null, gear = "D"), false))
    }

    @Test fun lockArmingAllowsExplicitUnlockButWaitsForAnUnknownLock() {
        assertTrue(parkedStartupReady(true, "lock", snapshot(false, locked = false), false))
        assertFalse(parkedStartupReady(true, "lock", snapshot(false, locked = null), false))
        assertFalse(parkedStartupReady(true, "lock", snapshot(false, locked = true), false))
        assertTrue(parkedStartupReady(true, "lock", snapshot(false, locked = true), true))
        assertFalse(parkedStartupReady(true, "off", snapshot(false, locked = false), false))
    }

    private fun snapshot(on: Boolean?, gear: String = "P", locked: Boolean? = null) =
        VehicleSnapshot(null, null, null, null, null, gear, on, locked)
}
