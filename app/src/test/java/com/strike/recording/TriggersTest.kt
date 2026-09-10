package com.strike.recording

import com.strike.surveillance.LOCK_FALLBACK_MS
import com.strike.vehicle.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TriggersTest {

    @Test
    fun losingThePowerReadingWhileWaitingForLockDoesNotRestartADrive() {
        assertEquals(false, driveWanted("continuous", car(accOn = null, gear = "P"), false))
        assertEquals(false, driveWanted("continuous", null, false))
    }

    @Test
    fun losingThePowerReadingDoesNotCutAnExistingContinuousDrive() {
        assertEquals(true, driveWanted("continuous", null, true))
        assertEquals(false, driveWanted("off", null, true))
    }

    @Test
    fun drivingModeStillStopsInParkWhenPowerIsUnavailable() {
        assertEquals(false, driveWanted("driving", car(accOn = null, gear = "P"), true))
    }

    @Test
    fun offRecordsNothingEvenWhileDriving() {
        assertEquals(false, shouldRecord("off", car(accOn = true, gear = "D")))
    }

    @Test
    fun continuousRecordsWheneverTheCarIsOn() {
        assertEquals(true, shouldRecord("continuous", car(accOn = true, gear = "P")))
        assertEquals(false, shouldRecord("continuous", car(accOn = false, gear = "P")))
    }

    @Test
    fun drivingWaitsForTheCarToLeavePark() {
        assertEquals(false, shouldRecord("driving", car(accOn = true, gear = "P")))
        assertEquals(true, shouldRecord("driving", car(accOn = true, gear = "D")))
        assertEquals(true, shouldRecord("driving", car(accOn = true, gear = "R")))
    }

    @Test
    fun continuousStillRecordsACarThatReportsNothing() {
        assertEquals(true, shouldRecord("continuous", null))
        assertEquals(true, shouldRecord("continuous", car(accOn = null, gear = null)))
    }

    @Test
    fun drivingWaitsForAGearItCanRead() {
        assertEquals(false, shouldRecord("driving", null))
        assertEquals(false, shouldRecord("driving", car(accOn = true, gear = null)))
    }

    @Test
    fun theKeyGoingOutArmsSurveillance() {
        assertEquals("smart", sentryMode(true, "smart", car(accOn = false, gear = "P")))
        assertEquals("continuous", sentryMode(true, "continuous", car(accOn = false, gear = "P")))
    }

    @Test
    fun surveillanceStandsDownWhileTheCarIsInUse() {
        assertEquals("off", sentryMode(true, "smart", car(accOn = true, gear = "P")))
        assertEquals("off", sentryMode(true, "smart", car(accOn = false, gear = "D")))
        assertEquals("off", sentryMode(false, "smart", car(accOn = false, gear = "P")))
    }

    @Test
    fun aCarThatWillNotSayLeavesSentryAlone() {
        assertNull(sentryMode(true, "smart", null))
        assertNull(sentryMode(true, "smart", car(accOn = null, gear = null)))
    }

    @Test
    fun onLockWaitsForTheDoors() {
        assertEquals("off", sentryMode(true, "smart", car(accOn = false, gear = "P", locked = false), "lock"))
        assertEquals("smart", sentryMode(true, "smart", car(accOn = false, gear = "P", locked = true), "lock"))
    }

    @Test
    fun onLockArmsAfterAMinuteWhenTheCarWillNotSay() {
        val parked = car(accOn = false, gear = "P", locked = null)
        assertEquals("off", sentryMode(true, "smart", parked, "lock", LOCK_FALLBACK_MS - 1))
        assertEquals("smart", sentryMode(true, "smart", parked, "lock", LOCK_FALLBACK_MS))
    }

    @Test
    fun onLockStillStandsDownWhileTheCarIsInUse() {
        assertEquals("off", sentryMode(true, "smart", car(accOn = true, gear = "P", locked = true), "lock"))
        assertEquals("off", sentryMode(true, "smart", car(accOn = false, gear = "D", locked = true), "lock"))
    }

    @Test
    fun theDriveRecorderAndSurveillanceNeverBothWantTheCamera() {
        for (accOn in listOf(true, false, null)) {
            for (gear in listOf("P", "D", "R", null)) {
                for (locked in listOf(true, false, null)) {
                    val snapshot = car(accOn, gear, locked)
                    val driving = shouldRecord("continuous", snapshot)
                    val watchingOff = watching(sentryMode(true, "continuous", snapshot, "off"))
                    val watchingLock = watching(sentryMode(true, "continuous", snapshot, "lock"))
                    assertEquals(false, driving && watchingOff)
                    assertEquals(false, driving && watchingLock)
                }
            }
        }
    }

    private fun watching(mode: String?): Boolean = mode != null && mode != "off"

    private fun car(accOn: Boolean?, gear: String?, locked: Boolean? = null): VehicleSnapshot =
        VehicleSnapshot(
            soc = 62, rangeKm = 210, batteryKwh = 28.0, fuelPercent = null, fuelRangeKm = null,
            gear = gear, accOn = accOn, locked = locked
        )
}
