package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ParkedDevicesTest {
    private var elapsedMs = 0L

    @Test fun unavailableDevicesAreRetriedWhenTheyBecomeReady() {
        val power = Any()
        val special = Any()
        var ready = false
        var powerAttempts = 0
        var specialAttempts = 0
        val devices = ParkedDevices(
            { powerAttempts++; if (ready) power else null },
            { specialAttempts++; if (ready) special else null },
            { elapsedMs }
        )
        devices.refresh()
        assertNull(devices.power)
        assertNull(devices.special)
        ready = true
        elapsedMs = 29_999L
        devices.refresh()
        assertEquals(1, powerAttempts)
        assertEquals(1, specialAttempts)
        assertNull(devices.power)
        elapsedMs++
        devices.refresh()
        assertSame(power, devices.power)
        assertSame(special, devices.special)
        assertEquals(2, powerAttempts)
        assertEquals(2, specialAttempts)
    }

    @Test fun aResolvedDeviceIsRetainedWhileTheOtherOneRetries() {
        for (powerFirst in listOf(false, true)) {
            elapsedMs = 0L
            val power = Any()
            val special = Any()
            var ready = false
            var powerAttempts = 0
            var specialAttempts = 0
            val devices = ParkedDevices(
                { powerAttempts++; if (ready || powerFirst) power else null },
                { specialAttempts++; if (ready || !powerFirst) special else null },
                { elapsedMs }
            )
            devices.refresh()
            elapsedMs += 30_000L
            devices.refresh()
            ready = true
            elapsedMs += 30_000L
            devices.refresh()
            assertSame(power, devices.power)
            assertSame(special, devices.special)
            assertEquals(if (powerFirst) 1 else 3, powerAttempts)
            assertEquals(if (powerFirst) 3 else 1, specialAttempts)
        }
    }

    @Test fun repeatedRailWritesDoNotHammerUnavailableOemServices() {
        var attempts = 0
        val devices = ParkedDevices({ attempts++; null }, { attempts++; null }, { elapsedMs })
        repeat(30) {
            repeat(100) { devices.refresh() }
            elapsedMs += 1_000L
        }
        assertEquals(2, attempts)
        devices.refresh()
        assertEquals(4, attempts)
    }

    @Test fun healthyDevicesNeedNoFurtherLookupsOrClockReads() {
        val power = Any()
        val special = Any()
        var attempts = 0
        var clockReads = 0
        val devices = ParkedDevices(
            { attempts++; power }, { attempts++; special }, { clockReads++; elapsedMs }
        )
        devices.refresh()
        repeat(48) {
            elapsedMs += 3_600_000L
            devices.refresh()
        }
        assertSame(power, devices.power)
        assertSame(special, devices.special)
        assertEquals(2, attempts)
        assertEquals(1, clockReads)
    }

    @Test fun aLongPauseMakesOneAttemptAndStartsANewRetryInterval() {
        var attempts = 0
        val devices = ParkedDevices({ attempts++; null }, { attempts++; null }, { elapsedMs })
        devices.refresh()
        elapsedMs += 16 * 3_600_000L
        repeat(100) { devices.refresh() }
        assertEquals(4, attempts)
        elapsedMs += 29_999L
        devices.refresh()
        assertEquals(4, attempts)
        elapsedMs++
        devices.refresh()
        assertEquals(6, attempts)
    }
}
