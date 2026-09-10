package com.strike.daemon

import com.strike.recording.sentryMode
import com.strike.recording.shouldRecord
import com.strike.surveillance.surveillanceReason
import com.strike.vehicle.VehicleSnapshot
import com.strike.vehicle.polledAccOnOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccMonitorTest {
    private var now = 1_000_000L
    private var reading: VehicleSnapshot? = car(true)
    private val monitor = AccMonitor({ reading }, { now })

    @Test
    fun parkingHandsTheCameraToSentryWithoutAnyAppMessages() {
        monitor.poll()
        assertTrue(shouldRecord("continuous", monitor.snapshot()))
        reading = car(false)
        pollAfter(1_000)
        assertTrue(monitor.isConfirmingOff())
        pollAfter(2_000)
        assertEquals("smart", mode())
        assertFalse(shouldRecord("continuous", monitor.snapshot()))
        assertFalse(accUnsafe(monitor.snapshot()?.accOn, now, now))
    }

    @Test
    fun aDaemonRestartedWhileParkedArmsWithoutAnOffBroadcast() {
        reading = car(false)
        monitor.poll()
        assertNull(mode())
        pollAfter(2_000)
        assertEquals("smart", mode())
    }

    @Test
    fun aPassingOffReadingDoesNotArm() {
        monitor.poll()
        reading = car(false)
        pollAfter(1_000)
        reading = car(true)
        pollAfter(1_000)
        assertEquals("off", mode())
        assertFalse(monitor.isConfirmingOff())
    }

    @Test
    fun turningTheCarOnDisarmsWithoutAnAppBroadcast() {
        monitor.edge(false)
        assertEquals("smart", mode())
        monitor.poll()
        assertEquals("off", mode())
        assertTrue(accUnsafe(monitor.snapshot()?.accOn, now, now))
    }

    @Test
    fun anOnBroadcastBlocksStaleOffReadingsAndBootOffBroadcasts() {
        monitor.edge(true)
        reading = car(false)
        monitor.poll()
        pollAfter(3_000)
        monitor.edge(false)
        assertEquals("off", mode())
        pollAfter(7_000)
        assertEquals("smart", mode())
    }

    @Test
    fun anOnBroadcastWinsOverADirectReadThatWasAlreadyInFlight() {
        lateinit var racing: AccMonitor
        racing = AccMonitor({ racing.edge(true); car(false) }, { now })
        racing.poll()
        assertEquals(true, racing.snapshot()?.accOn)
    }

    @Test
    fun missingReadingsCannotArmAColdDaemon() {
        reading = null
        monitor.poll()
        repeat(10) { pollAfter(5_000) }
        assertNull(mode())
        assertTrue(accUnsafe(monitor.snapshot()?.accOn, now, now))
        assertEquals("Waiting for the car's power state",
            surveillanceReason(true, monitor.snapshot(), "off", mode(), monitor.isConfirmingOff()))
    }

    @Test
    fun invalidAndAccessoryPowerLevelsCannotArm() {
        for (level in listOf(null, -1, 1, 4, 255)) {
            reading = car(polledAccOnOf(level))
            monitor.poll()
            pollAfter(3_000)
            assertNull(mode())
        }
        assertEquals(false, polledAccOnOf(0))
        assertEquals(true, polledAccOnOf(2))
        assertEquals(true, polledAccOnOf(3))
    }

    @Test
    fun onLockWaitsForAnActualLockReading() {
        reading = car(false, locked = false)
        monitor.poll()
        pollAfter(2_000)
        assertEquals("off", mode("lock"))
        reading = car(false, locked = true)
        pollAfter(1_000)
        assertEquals("smart", mode("lock"))
    }

    @Test
    fun unknownLockUsesTheExistingOneMinuteFallback() {
        reading = car(false)
        monitor.poll()
        pollAfter(2_000)
        assertEquals("off", mode("lock"))
        pollAfter(60_000)
        assertEquals("smart", mode("lock"))
    }

    @Test
    fun leavingParkPreventsArmingEvenWhenPowerReadsOff() {
        reading = car(false, gear = "D")
        monitor.poll()
        pollAfter(2_000)
        assertEquals("off", mode())
    }

    @Test
    fun appPollingCanArmWhenTheDaemonSdkIsUnavailable() {
        reading = null
        monitor.fromApp(car(true))
        now += 5_000
        monitor.fromApp(car(false))
        now += 5_000
        monitor.fromApp(car(false))
        assertEquals("smart", mode())
    }

    @Test
    fun repeatingASingleAppReadingCannotConfirmParking() {
        reading = null
        monitor.fromApp(car(false))
        repeat(4) { pollAfter(3_000) }
        assertNull(mode())
    }

    @Test
    fun staleAppReadingsDoNotKeepTheDeterrentPermitted() {
        reading = null
        monitor.fromApp(car(false))
        now += 5_000
        monitor.fromApp(car(false))
        repeat(5) { pollAfter(5_000) }
        assertNull(monitor.snapshot()?.accOn)
        assertTrue(accUnsafe(monitor.snapshot()?.accOn, now, now))
    }

    @Test
    fun aFreshDirectReadingWinsOverAnAppPoll() {
        monitor.poll()
        monitor.fromApp(car(false))
        now += 5_000
        monitor.fromApp(car(false))
        assertEquals("off", mode())
    }

    @Test
    fun anOldAppReadingCannotUndoANewerDirectTransition() {
        monitor.fromApp(car(true))
        reading = car(false)
        pollAfter(1_000)
        pollAfter(2_000)
        assertEquals("smart", mode())
        reading = null
        pollAfter(1_000)
        assertNull(monitor.snapshot()?.accOn)
    }

    @Test
    fun aBlockedPollCannotKeepTheLastReadingFreshForever() {
        monitor.edge(false)
        now += 20_000
        assertNull(monitor.snapshot())
    }

    @Test
    fun reusingAnAppSampleDoesNotExtendItsPermissionToDarkenThePanel() {
        reading = null
        monitor.fromApp(car(false))
        now += 5_000
        monitor.fromApp(car(false))
        val observed = now
        pollAfter(19_000)
        assertEquals(observed, monitor.observedAtMs())
        val parked = monitor.snapshot()?.accOn
        assertFalse(accUnsafe(parked, monitor.observedAtMs(), now))
        now += 1_000
        assertTrue(accUnsafe(parked, monitor.observedAtMs(), now))
    }

    private fun pollAfter(elapsedMs: Long) {
        now += elapsedMs
        monitor.poll()
    }

    private fun mode(arm: String = "off") =
        sentryMode(true, "smart", monitor.snapshot(), arm, monitor.parkedForMs())

    private fun car(on: Boolean?, gear: String? = "P", locked: Boolean? = null) =
        VehicleSnapshot(null, null, null, null, null, gear, on, locked)
}
